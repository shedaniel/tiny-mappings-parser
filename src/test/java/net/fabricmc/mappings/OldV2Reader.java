/*
 * Copyright 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.mappings;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fabricmc.mappings.ClassEntry;
import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.ExtendedMappings;
import net.fabricmc.mappings.FieldEntry;
import net.fabricmc.mappings.MappedStringDeduplicator;
import net.fabricmc.mappings.MethodEntry;
import net.fabricmc.mappings.MappedStringDeduplicator.Category;
import net.fabricmc.mappings.model.CommentEntry;
import net.fabricmc.mappings.model.Comments;
import net.fabricmc.mappings.model.CommentsImpl;
import net.fabricmc.mappings.model.LocalVariable;
import net.fabricmc.mappings.model.LocalVariableEntry;
import net.fabricmc.mappings.model.MethodParameter;
import net.fabricmc.mappings.model.MethodParameterEntry;
import net.fabricmc.mappings.model.CommentEntry.Class;
import net.fabricmc.mappings.model.CommentEntry.Field;
import net.fabricmc.mappings.model.CommentEntry.LocalVariableComment;
import net.fabricmc.mappings.model.CommentEntry.Method;
import net.fabricmc.mappings.model.CommentEntry.Parameter;

/**
 * A factory for the Tiny V2 mapping parser.
 */
public final class OldV2Reader {
	private static class Visitor implements TinyVisitor {
		private class ClassBits {
			final String[] names;
			final Collection<BiFunction<UnaryOperator<String>[], ToIntFunction<String>, FieldEntry>> fields = new ArrayList<>();
			final Collection<BiFunction<UnaryOperator<String>[], ToIntFunction<String>, MethodEntry>> methods = new ArrayList<>();
			final Map<String, Collection<BiFunction<EntryTriple[], ToIntFunction<String>, MethodParameterEntry>>> parameters = new HashMap<>();
			final Map<String, Collection<BiFunction<EntryTriple[], ToIntFunction<String>, LocalVariableEntry>>> locals = new HashMap<>();

			public ClassBits(String... names) {
				assert Arrays.stream(names).filter(Objects::nonNull).noneMatch(String::isEmpty);
				this.names = names;
			}

			public Function<ToIntFunction<String>, ClassEntry> classFactory() {
				return namespaceIndex -> namespace -> names[namespaceIndex.applyAsInt(namespace)];
			}

			private <T> BiFunction<UnaryOperator<String>[], ToIntFunction<String>, T> memberFactory(BiFunction<EntryTriple[], ToIntFunction<String>, T> memberEntryFactory, String descriptor, String... names) {
				assert !descriptor.isEmpty();
				assert this.names.length == names.length;

				return (remapper, namespaceIndex) -> {
					assert remapper.length == names.length;
					EntryTriple[] members = new EntryTriple[names.length];

					for (int i = 0; i < names.length; i++) {
						if (names[i] == null) continue;

						assert !names[i].isEmpty();
						members[i] = new EntryTriple(this.names[i], names[i], remapper[i].apply(descriptor));
					}

					return memberEntryFactory.apply(members, namespaceIndex);
				};
			}

			public void addField(String descriptor, String... names) {
				fields.add(memberFactory((fields, namespaceIndex) -> namespace -> fields[namespaceIndex.applyAsInt(namespace)], descriptor, names));
			}

			public void addMethod(String descriptor, String... names) {
				methods.add(memberFactory((methods, namespaceIndex) -> namespace -> methods[namespaceIndex.applyAsInt(namespace)], descriptor, names));
			}

			public void addParameter(EntryTriple method, int localVariableIndex, String... names) {
				assert this.names[0].equals(method.getOwner());
				addParameter(method.getName() + method.getDesc(), localVariableIndex, names);
			}

			public void addParameter(String method, int localVariableIndex, String... names) {
				assert this.names.length == names.length;

				parameters.computeIfAbsent(method, k -> new ArrayList<>()).add((methods, namespaceIndex) -> {
					assert methods.length == names.length;
					MethodParameter[] parameters = new MethodParameter[names.length];

					for (int i = 0; i < names.length; i++) {
						if (names[i] == null) continue;

						assert !names[i].isEmpty();
						parameters[i] = new MethodParameter(methods[i], names[i], localVariableIndex);
					}

					return namespace -> parameters[namespaceIndex.applyAsInt(namespace)];
				});
			}

			public void addLocal(EntryTriple method, int localVariableIndex, int indexStartOffset, int lvtIndex, String... names) {
				assert this.names[0].equals(method.getOwner());
				addLocal(method.getName() + method.getDesc(), localVariableIndex, indexStartOffset, lvtIndex, names);
			}

			public void addLocal(String method, int localVariableIndex, int indexStartOffset, int lvtIndex, String... names) {
				assert this.names.length == names.length;

				locals.computeIfAbsent(method, k -> new ArrayList<>()).add((methods, namespaceIndex) -> {
					assert methods.length == names.length;
					LocalVariable[] locals = new LocalVariable[names.length];

					for (int i = 0; i < names.length; i++) {
						if (names[i] == null) continue;

						assert !names[i].isEmpty();
						locals[i] = new LocalVariable(methods[i], names[i], localVariableIndex, indexStartOffset, lvtIndex);
					}

					return namespace -> locals[namespaceIndex.applyAsInt(namespace)];
				});
			}
		}

		private final MappedStringDeduplicator depuplicator;
		private final boolean keepParams, keepLocals, keepComments;
		private final Collection<ClassBits> classes = new ArrayList<>();
		private List<String> namespaces;

		private ClassBits currentClass;
		private String currentClassName;
		private EntryTriple currentMemberName;
		private MethodParameter currentParameterName;
		private LocalVariable currentLocalVariableName;

		final Comments comments = new CommentsImpl();
		private List<String> currentComments;
		private TinyState currentCommentType;

		public Visitor(MappedStringDeduplicator deduplicator) {
			this(deduplicator, true, true, true);
		}

		public Visitor(MappedStringDeduplicator deduplicator, boolean keepParams, boolean keepLocals, boolean keepComments) {
			this.depuplicator = deduplicator;
			this.keepParams = keepParams;
			this.keepLocals = keepLocals;
			this.keepComments = keepComments;
		}

		@Override
		public MappedStringDeduplicator deduplicator() {
			return depuplicator;
		}

		@Override
		public void start(String[] namespaces, Map<String, String> properties) {
			this.namespaces = Arrays.asList(namespaces);
		}

		@Override
		public void pushClass(String[] parts) {
			assert parts.length > 0;

			currentClassName = parts[0];
			assert currentClassName != null && !currentClassName.isEmpty();

			classes.add(currentClass = new ClassBits(parts));
			setNewCommentType(TinyState.CLASS);
		}

		@Override
		public void pushField(String[] parts, String descriptor) {
			assert parts.length > 0;
			updateCurrentMember(parts[0], descriptor);

			currentClass.addField(descriptor, parts);
			setNewCommentType(TinyState.FIELD);
		}

		@Override
		public void pushMethod(String[] parts, String descriptor) {
			assert parts.length > 0;
			updateCurrentMember(parts[0], descriptor);

			currentClass.addMethod(descriptor, parts);
			setNewCommentType(TinyState.METHOD);
		}

		@Override
		public void pushParameter(String[] parts, int localVariableIndex) {
			assert parts.length > 0;

			if (keepParams) {
				assert Arrays.stream(parts).filter(Objects::nonNull).noneMatch(String::isEmpty);
				currentParameterName = new MethodParameter(currentMemberName, parts[0], localVariableIndex);

				currentClass.addParameter(currentParameterName.getMethod(), localVariableIndex, parts);
			}
			setNewCommentType(TinyState.PARAMETER);
		}

		@Override
		public void pushLocalVariable(String[] parts, int localVariableIndex, int localVariableStartOffset, int localVariableTableIndex) {
			assert parts.length > 0;

			if (keepLocals) {
				assert Arrays.stream(parts).filter(Objects::nonNull).noneMatch(String::isEmpty);
				currentLocalVariableName = new LocalVariable(currentMemberName, parts[0], localVariableIndex, localVariableStartOffset, localVariableTableIndex);

				currentClass.addLocal(currentLocalVariableName.getMethod(), localVariableIndex, localVariableStartOffset, localVariableTableIndex, parts);
			}
			setNewCommentType(TinyState.LOCAL_VARIABLE);
		}

		@Override
		public void pushComment(String comment) {
			if (!keepComments) return;

			if (currentComments == null) {
				switch (currentCommentType) {
				case CLASS:
					comments.getClassComments().add(new CommentEntry.Class(currentComments = new ArrayList<>(), currentClassName));
					break;
				case FIELD:
					comments.getFieldComments().add(new CommentEntry.Field(currentComments = new ArrayList<>(), currentMemberName));
					break;
				case METHOD:
					comments.getMethodComments().add(new CommentEntry.Method(currentComments = new ArrayList<>(), currentMemberName));
					break;
				case PARAMETER:
					if (!keepParams) return;
					comments.getMethodParameterComments().add(new CommentEntry.Parameter(currentComments = new ArrayList<>(), currentParameterName));
					break;
				case LOCAL_VARIABLE:
					if (!keepLocals) return;
					comments.getLocalVariableComments().add(new CommentEntry.LocalVariableComment(currentComments = new ArrayList<>(), currentLocalVariableName));
					break;
				default:
					throw new RuntimeException("Unexpected comment without parent: " + comment);
				}
			}

			currentComments.add(comment);
		}

		private void setNewCommentType(TinyState type) {
			currentCommentType = type;
			currentComments = null;
		}

		private void updateCurrentMember(String name, String descriptor) {
			assert name != null && !name.isEmpty();
			currentMemberName = new EntryTriple(currentClassName, name, descriptor);
		}

		private static class MappingsImpl implements ExtendedMappings {
			private final Collection<ClassEntry> classEntries;
			private final Collection<MethodEntry> methodEntries;
			private final Collection<FieldEntry> fieldEntries;
			private final Collection<MethodParameterEntry> methodParameterEntries;
			private final Collection<LocalVariableEntry> localVariableEntries;
			private final Collection<String> namespaces;
			private final Comments comments;

			public MappingsImpl(Collection<ClassEntry> classEntries, Collection<MethodEntry> methodEntries, Collection<FieldEntry> fieldEntries, Collection<MethodParameterEntry> methodParameterEntries, Collection<LocalVariableEntry> localVariableEntries, Collection<String> namespaces, Comments comments) {
				this.classEntries = classEntries;
				this.methodEntries = methodEntries;
				this.fieldEntries = fieldEntries;
				this.methodParameterEntries = methodParameterEntries;
				this.localVariableEntries = localVariableEntries;
				this.namespaces = namespaces;
				this.comments = comments;
			}

			@Override
			public Collection<ClassEntry> getClassEntries() {
				return classEntries;
			}

			@Override
			public Collection<MethodEntry> getMethodEntries() {
				return methodEntries;
			}

			@Override
			public Collection<FieldEntry> getFieldEntries() {
				return fieldEntries;
			}

			@Override
			public Collection<MethodParameterEntry> getMethodParameterEntries() {
				return methodParameterEntries;
			}

			@Override
			public Collection<LocalVariableEntry> getLocalVariableEntries() {
				return localVariableEntries;
			}

			@Override
			public Collection<String> getNamespaces() {
				return namespaces;
			}

			@Override
			public Comments getComments() {
				return comments;
			}
		}

		public ExtendedMappings getMappings() {
			@SuppressWarnings("unchecked") //We'll be careful Java
			UnaryOperator<String>[] remappers = new UnaryOperator[namespaces.size()];
			remappers[0] = UnaryOperator.identity();

			if (remappers.length > 1) {
				@SuppressWarnings("unchecked") //Super careful, no accidents
				Map<String, String>[] classPools = new HashMap[namespaces.size() - 1];
				for (int i = 0; i < classPools.length; i++) classPools[i] = new HashMap<>();

				for (ClassBits clazz : classes) {
					assert clazz.names.length == remappers.length;

					for (int i = 1; i < clazz.names.length; i++) {
						classPools[i - 1].put(clazz.names[0], clazz.names[i]);
					}
				}

				Pattern classFinder = Pattern.compile("L([^;]+);");
				for (int i = 0; i < classPools.length; i++) {
					Map<String, String> classPool = classPools[i]; //Unpack here so the lambda only needs capture the map

					remappers[i + 1] = desc -> {
						StringBuffer buf = new StringBuffer();

						Matcher matcher = classFinder.matcher(desc);
						while (matcher.find()) {
							String name = matcher.group(1);
							matcher.appendReplacement(buf, Matcher.quoteReplacement('L' + classPool.getOrDefault(name, name) + ';'));
						}
						matcher.appendTail(buf);

						return buf.toString();
					};
				}
			}

			Collection<ClassEntry> classEntries = new ArrayList<>(classes.size());
			Collection<MethodEntry> methodEntries = new ArrayList<>();
			Collection<FieldEntry> fieldEntries = new ArrayList<>();
			Collection<MethodParameterEntry> methodParameterEntries = keepParams ? new ArrayList<>() : Collections.emptyList();
			Collection<LocalVariableEntry> localVariableEntries = keepLocals ? new ArrayList<>() : Collections.emptyList();

			ToIntFunction<String> namespaceIndex = namespaces::indexOf;
			String rootNamespace = namespaces.get(0);

			for (ClassBits clazz : classes) {
				classEntries.add(clazz.classFactory().apply(namespaceIndex));

				for (BiFunction<UnaryOperator<String>[], ToIntFunction<String>, FieldEntry> factory : clazz.fields) {
					fieldEntries.add(factory.apply(remappers, namespaceIndex));
				}

				Collection<MethodEntry> methods = new ArrayList<>();
				for (BiFunction<UnaryOperator<String>[], ToIntFunction<String>, MethodEntry> factory : clazz.methods) {
					methods.add(factory.apply(remappers, namespaceIndex));
				}
				methodEntries.addAll(methods);

				if (keepParams || keepLocals) {
					for (MethodEntry methodEntry : methods) {
						EntryTriple method = methodEntry.get(rootNamespace);
						String methodName = method.getName() + method.getDesc();

						EntryTriple[] triples = null; //Lazily fill when needed

						if (keepParams) {
							Collection<BiFunction<EntryTriple[], ToIntFunction<String>, MethodParameterEntry>> paramFactories = clazz.parameters.get(methodName);
							if (paramFactories != null) {
								triples = new EntryTriple[namespaces.size()];

								triples[0] = method;
								for (int i = 1; i < namespaces.size(); i++) {
									triples[i] = methodEntry.get(namespaces.get(i));
								}

								for (BiFunction<EntryTriple[], ToIntFunction<String>, MethodParameterEntry> factory : paramFactories) {
									methodParameterEntries.add(factory.apply(triples, namespaceIndex));
								}
							}
						}

						if (keepLocals) {
							Collection<BiFunction<EntryTriple[], ToIntFunction<String>, LocalVariableEntry>> localFactories = clazz.locals.get(methodName);
							if (localFactories != null) {
								if (triples == null) triples = namespaces.stream().map(methodEntry::get).toArray(EntryTriple[]::new);

								for (BiFunction<EntryTriple[], ToIntFunction<String>, LocalVariableEntry> factory : localFactories) {
									localVariableEntries.add(factory.apply(triples, namespaceIndex));
								}
							}
						}
					}
				}
			}

			((ArrayList<?>) methodEntries).trimToSize();
			((ArrayList<?>) fieldEntries).trimToSize();
			if (keepParams) ((ArrayList<?>) methodParameterEntries).trimToSize();
			if (keepLocals) ((ArrayList<?>) localVariableEntries).trimToSize();
			return new MappingsImpl(classEntries, methodEntries, fieldEntries, methodParameterEntries, localVariableEntries, namespaces, keepComments ? trim(comments) : Comments.empty());
		}

		private static Comments trim(Comments comments) {
			List<Class> classComments = trim(comments.getClassComments());
			List<Field> fieldComments = trim(comments.getFieldComments());
			List<Method> methodComments = trim(comments.getMethodComments());
			List<Parameter> methodParameterComments = trim(comments.getMethodParameterComments());
			List<LocalVariableComment> localVariableComments = trim(comments.getLocalVariableComments());

			return classComments.isEmpty() && fieldComments.isEmpty() && methodComments.isEmpty() && methodParameterComments.isEmpty() && localVariableComments.isEmpty() ? 
					Comments.empty() : new CommentsImpl(classComments, fieldComments, methodComments, methodParameterComments, localVariableComments);
		}

		private static <T extends CommentEntry> List<T> trim(Collection<T> comments) {
			List<T> shorterComments = new ArrayList<>(comments);

			for (Iterator<T> it = shorterComments.iterator(); it.hasNext();) {
				T comment = it.next();

				if (comment.getComments().stream().allMatch(line -> {
					int strLen;
			        if (line == null || (strLen = line.length()) == 0) {
			            return true;
			        }

			        for (int i = 0; i < strLen; i++) {
			            if (!Character.isWhitespace(line.charAt(i))) {
			                return false;
			            }
			        }
			        return true;
				})) {
					it.remove();
				}
			}

			if (shorterComments.isEmpty()) {
				return Collections.emptyList();
			} else {
				((ArrayList<?>) shorterComments).trimToSize();
				return shorterComments;
			}
		}
	}

	private static final String HEADER_MARKER = "tiny";
	private static final char INDENT = '\t';
	private static final String SPACER_STRING = "\t";
	private static final String ESCAPED_NAMES_PROPERTY = "escaped-names";

	public static ExtendedMappings fullyRead(BufferedReader reader, boolean deduplicate) throws IOException {
		return fullyRead(reader.readLine(), reader, deduplicate ? new MappedStringDeduplicator.MapBased() : MappedStringDeduplicator.EMPTY);
	}

	private static ExtendedMappings fullyRead(String firstLine, BufferedReader reader, MappedStringDeduplicator deduplicator) throws IOException {
		Visitor visitor = new Visitor(deduplicator);
		visit(firstLine, reader, visitor);
		return visitor.getMappings();
	}

	private static void visit(String firstLine, BufferedReader reader, TinyVisitor visitor) throws IOException {
		if (firstLine == null) throw new IllegalArgumentException("Empty reader!");

		final int namespaceCount;
		final boolean escapedNames; {
			final String[] parts = firstLine.split(SPACER_STRING, -1);
			if (parts.length < 5 || !parts[0].equals(HEADER_MARKER)) {
				throw new IllegalArgumentException("Unsupported format!");
			}

			/*final int majorVersion;
			try {
				majorVersion = Integer.parseInt(parts[1]);
			} catch (NumberFormatException ex) {
				throw new IllegalArgumentException("Invalid major version!", ex);
			}

			final int minorVersion;
			try {
				minorVersion = Integer.parseInt(parts[2]);
			} catch (NumberFormatException ex) {
				throw new IllegalArgumentException("Invalid minor version!", ex);
			}*/

			String namespaces[] = Arrays.copyOfRange(parts, 3, parts.length);
			Map<String, String> properties = readMetadata(reader);

			visitor.start(namespaces, properties);

			namespaceCount = namespaces.length;
			escapedNames = properties.containsKey(ESCAPED_NAMES_PROPERTY);			
		}

		int lastIndent = -1;
		final TinyState[] stack = new TinyState[4]; // max depth 4
		for (String line = reader.readLine(); line != null; line = reader.readLine()) {
			try {
				int currentIndent = countIndent(line);
				if (currentIndent > lastIndent + 1)
					throw new IllegalArgumentException("Broken indent! Maximum " + (lastIndent + 1) + ", actual " + currentIndent);
				if (currentIndent <= lastIndent) {
					visitor.pop(lastIndent - currentIndent + 1);
				}
				lastIndent = currentIndent;

				final String[] parts = line.split(SPACER_STRING, -1);
				final TinyState currentState = TinyState.get(currentIndent, parts[currentIndent]);

				if (!currentState.checkPartCount(currentIndent, parts.length, namespaceCount)) {
					throw new IllegalArgumentException("Wrong number of parts for definition of a " + currentState + "!");
				}

				if (!currentState.checkStack(stack, currentIndent)) {
					throw new IllegalStateException("Invalid stack " + Arrays.toString(stack) + " for a " + currentState + " at position" + currentIndent + "!");
				}

				stack[currentIndent] = currentState;

				currentState.visit(visitor, parts, currentIndent, escapedNames);
			} catch (RuntimeException ex) {
				throw new IOException("Error on line \"" + line + "\"!", ex);
			}
		}

		if (lastIndent > -1) {
			visitor.pop(lastIndent + 1);
		}
		visitor.finish();
	}

	private static Map<String, String> readMetadata(BufferedReader reader) throws IOException, IllegalArgumentException {
		Map<String, String> properties = new LinkedHashMap<>();

		String line;
		reader.mark(8192);
		out: while ((line = reader.readLine()) != null) {
			switch (countIndent(line)) {
			case 0: {
				reader.reset();
				break out;
			}

			case 1: {
				String[] elements = line.split(SPACER_STRING, -1); // Care about "" values
				properties.put(elements[1], elements.length == 2 ? null : elements[2]);
				break;
			}

			default: {
				throw new IllegalArgumentException("Invalid indent in header! Encountered \"" + line + "\"!");
			}
			}

			reader.mark(8192);
		}

		return properties;
	}

	private static int countIndent(String st) {
		final int len = st.length();
		int ret = 0;
		while (ret < len && st.charAt(ret) == INDENT) {
			ret++;
		}
		return ret;
	}

	private OldV2Reader() {
	}

	private enum TinyState {
		// c names...
		CLASS(1) {
			@Override
			boolean checkStack(TinyState[] stack, int currentIndent) {
				return currentIndent == 0;
			}

			@Override
			void visit(TinyVisitor visitor, String[] parts, int indent, boolean escapedStrings) {
				visitor.pushClass(classNames(visitor, parts, indent, escapedStrings));
			}
		},
		// f desc names...
		FIELD(2) {
			@Override
			boolean checkStack(TinyState[] stack, int currentIndent) {
				return currentIndent == 1 && stack[currentIndent - 1] == CLASS;
			}

			@Override
			void visit(TinyVisitor visitor, String[] parts, int indent, boolean escapedStrings) {
				visitor.pushField(names(visitor, parts, indent, escapedStrings), fieldDesc(visitor, parts[indent + 1], escapedStrings));
			}
		},
		// m desc names...
		METHOD(2) {
			@Override
			boolean checkStack(TinyState[] stack, int currentIndent) {
				return currentIndent == 1 && stack[currentIndent - 1] == CLASS;
			}

			@Override
			void visit(TinyVisitor visitor, String[] parts, int indent, boolean escapedStrings) {
				visitor.pushMethod(names(visitor, parts, indent, escapedStrings), methodDesc(visitor, parts[indent + 1], escapedStrings));
			}
		},
		// p lvIndex names...
		PARAMETER(2) {
			@Override
			boolean checkStack(TinyState[] stack, int currentIndent) {
				return currentIndent == 2 && stack[currentIndent - 1] == METHOD;
			}

			@Override
			void visit(TinyVisitor visitor, String[] parts, int indent, boolean escapedStrings) {
				visitor.pushParameter(names(visitor, parts, indent, escapedStrings), Integer.parseInt(parts[indent + 1]));
			}
		},
		// v lvIndex lvStartOffset lvtIndex names...
		LOCAL_VARIABLE(4) {
			@Override
			boolean checkStack(TinyState[] stack, int currentIndent) {
				return currentIndent == 2 && stack[currentIndent - 1] == METHOD;
			}

			@Override
			void visit(TinyVisitor visitor, String[] parts, int indent, boolean escapedStrings) {
				visitor.pushLocalVariable(names(visitor, parts, indent, escapedStrings), Integer.parseInt(parts[indent + 1]), Integer.parseInt(parts[indent + 2]), Integer.parseInt(parts[indent + 3]));
			}
		},
		// c comment
		COMMENT(2, false) {
			@Override
			boolean checkStack(TinyState[] stack, int currentIndent) {
				if (currentIndent == 0)
					return false;
				switch (stack[currentIndent - 1]) {
				case CLASS:
				case METHOD:
				case FIELD:
				case PARAMETER:
				case LOCAL_VARIABLE:
					// Use a whitelist
					return true;
				default:
					return false;
				}
			}

			@Override
			void visit(TinyVisitor visitor, String[] parts, int indent, boolean escapedStrings) {
				visitor.pushComment(unescape(parts[indent + 1])); //Apparently always escaped
			}
		};

		final int actualParts;
		private final boolean namespaced;

		TinyState(int actualParts) {
			this(actualParts, true);
		}

		TinyState(int actualParts, boolean namespaced) {
			this.actualParts = actualParts;
			this.namespaced = namespaced;
		}

		static TinyState get(int indent, String identifier) {
			switch (identifier) {
			case "c":
				return indent == 0 ? CLASS : COMMENT;
			case "m":
				return METHOD;
			case "f":
				return FIELD;
			case "p":
				return PARAMETER;
			case "v":
				return LOCAL_VARIABLE;
			default:
				throw new IllegalArgumentException("Invalid identifier \"" + identifier + "\"!");
			}
		}

		boolean checkPartCount(int indent, int partCount, int namespaceCount) {
			return partCount - indent == (namespaced ? namespaceCount + actualParts : actualParts);
		}

		abstract boolean checkStack(TinyState[] stack, int currentIndent);

		abstract void visit(TinyVisitor visitor, String[] parts, int indent, boolean escapedStrings);

		String[] names(TinyVisitor visitor, String[] parts, int indent, boolean escapedStrings) {
			return makeGetter(visitor, Category.NAME, parts, indent, escapedStrings);
		}

		String[] classNames(TinyVisitor visitor, String[] parts, int indent, boolean escapedStrings) {
			return makeGetter(visitor, Category.CLASS_NAME, parts, indent, escapedStrings);
		}

		private String[] makeGetter(TinyVisitor visitor, Category category, String[] parts, int indent, boolean escapedStrings) {
			int offset = indent + actualParts;

			assert parts.length >= offset;
			String[] bits = new String[parts.length - offset];

			for (int i = 0; offset < parts.length; i++, offset++) {
				String bit = unescapeOpt(parts[offset], escapedStrings);
				if (!bit.isEmpty()) bits[i] = visitor.deduplicator().deduplicate(category, bit);
			}

			return bits;
		}

		static String methodDesc(TinyVisitor visitor, String part, boolean escapeStrings) {
			assert !part.isEmpty();
			return visitor.deduplicator().deduplicate(Category.METHOD_DESCRIPTOR, unescapeOpt(part, escapeStrings));
		}

		static String fieldDesc(TinyVisitor visitor, String part, boolean escapeStrings) {
			assert !part.isEmpty();
			return visitor.deduplicator().deduplicate(Category.FIELD_DESCRIPTOR, unescapeOpt(part, escapeStrings));
		}

		private static String unescapeOpt(String raw, boolean escapedStrings) {
			return escapedStrings ? unescape(raw) : raw;
		}

		private static final String TO_ESCAPE = "\\\n\r\0\t";
		private static final String ESCAPED = "\\nr0t";
		private static String unescape(String str) {
			// copied from matcher, lazy!
			int pos = str.indexOf('\\');
			if (pos < 0) return str;

			StringBuilder ret = new StringBuilder(str.length() - 1);
			int start = 0;

			do {
				ret.append(str, start, pos);
				pos++;
				int type;

				if (pos >= str.length()) {
					throw new RuntimeException("incomplete escape sequence at the end");
				} else if ((type = ESCAPED.indexOf(str.charAt(pos))) < 0) {
					throw new RuntimeException("invalid escape character: \\" + str.charAt(pos));
				} else {
					ret.append(TO_ESCAPE.charAt(type));
				}

				start = pos + 1;
			} while ((pos = str.indexOf('\\', start)) >= 0);

			return ret.append(str, start, str.length()).toString();
		}
	}

	private interface TinyVisitor {
		/**
		 * Return the {@link MappedStringDeduplicator string deduplicator} to be used for the 
		 * given {@link String} arrays in the various push methods.
		 * 
		 * <p>This should be expected to be called multiple time so should avoid creating
		 * a new instance after the first call.
		 * 
		 * @return The deduplicator to apply to the push method's String arrays
		 */
		default MappedStringDeduplicator deduplicator() {
			return MappedStringDeduplicator.EMPTY;
		}

		/**
		 * Start visiting a new mapping and collect basic mapping information.
		 *
		 * <p>It's recommended to collect the index of namespaces you want to track
		 * from the given array.
		 *
		 * @param namespaces the namespaces declared in the Tiny V2 mapping
		 * @param properties additional properties that are defined in the Tiny V2 header
		 */
		void start(String[] namespaces, Map<String, String> properties);

		/**
		 * Visit a class.
		 *
		 * <p>{@link #start(String[], Map)} is called before this call and the visit stack
		 * is empty.
		 *
		 * @param name the mappings
		 */
		void pushClass(String[] parts);

		/**
		 * Visit a field.
		 *
		 * <p>{@link #pushClass(String[])} is called before this call and the last
		 * element in the visit stack is a class.
		 *
		 * @param parts the mappings
		 * @param descriptor the descriptor in the index 0 namespace's mapping
		 */
		void pushField(String[] parts, String descriptor);

		/**
		 * Visit a method.
		 *
		 * <p>{@link #pushClass(String[])} is called before this call and the last
		 * element in the visit stack is a class.
		 *
		 * @param parts the mappings
		 * @param descriptor the descriptor in the index 0 namespace's mapping
		 */
		void pushMethod(String[] parts, String descriptor);

		/**
		 * Visits a method parameter.
		 *
		 * <p>{@link #pushMethod(String[], String)} is called
		 * before this call and the last element in the visit stack is a method.
		 *
		 * @param parts the mappings
		 * @param localVariableIndex the local variable index
		 */
		void pushParameter(String[] parts, int localVariableIndex);

		/**
		 * Visits a method's local variable.
		 *
		 * <p>{@link #pushMethod(String[], String)} is called
		 * before this call and the last element in the visit stack is a method.
		 *
		 * @param parts the mappings
		 * @param localVariableIndex the local variable index
		 * @param localVariableStartOffset the local variable start offset
		 * @param localVariableTableIndex the local variable table index
		 */
		void pushLocalVariable(String[] parts, int localVariableIndex, int localVariableStartOffset, int localVariableTableIndex);

		/**
		 * Visits a comment (JavaDoc).
		 *
		 * <p>One of the other push methods is called before this call; the visit stack is not empty, and
		 * the last element in the visit stack can be commented (i.e. everything other than a comment).
		 *
		 * @param comment the comment
		 */
		void pushComment(String comment);

		/**
		 * Remove a few elements from the context stack.
		 *
		 * <p>The sum of {@code count} of all pop calls should equal to the number of push calls when
		 * each class visit is finished.
		 *
		 * @param count the number of elements removed
		 */
		default void pop(int count) {
		}

		/**
		 * Finish visiting the mapping file
		 * 
		 * <p>Nothing else will be called after this.
		 */
		default void finish() {
		}
	}
}