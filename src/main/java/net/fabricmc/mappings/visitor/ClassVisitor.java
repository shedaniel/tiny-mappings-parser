package net.fabricmc.mappings.visitor;

public interface ClassVisitor {
	MethodVisitor visitMethod(long offset, String[] names, String descriptor);

	FieldVisitor visitField(long offset, String[] names, String descriptor);

	void visitComment(String line);
}