/*
 * Copyright 2020 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package net.fabricmc.mappings.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import org.apache.commons.io.FileUtils;

import com.google.common.net.UrlEscapers;

import net.fabricmc.mappings.ClassEntry;
import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.ExtendedMappings;
import net.fabricmc.mappings.FieldEntry;
import net.fabricmc.mappings.MappingsProvider;
import net.fabricmc.mappings.MethodEntry;
import net.fabricmc.mappings.TinyV2VisitorBridge;

class ImplementationDifferenceTest {
	private Path getTiny() throws IOException {
		File tempJar = File.createTempFile("1.15.1-test-mappings", ".jar");
		Path temp = File.createTempFile("1.15.1-test-mappings", ".tiny").toPath();

		FileUtils.copyURLToFile(new URL("https://maven.fabricmc.net/net/fabricmc/yarn/" + UrlEscapers.urlPathSegmentEscaper().escape("1.15.1+build.23/yarn-1.15.1+build.23-v2.jar")), tempJar);
		try (FileSystem fileSystem = FileSystems.newFileSystem(tempJar.toPath(), null)) {
			Files.copy(fileSystem.getPath("mappings/mappings.tiny"), temp, StandardCopyOption.REPLACE_EXISTING);
		}

		return temp;
	}

	@Test
	void fullTest() throws IOException {
		long time = System.nanoTime();

		Path mappings = getTiny();

		System.out.printf("Mappings took %.5f milliseconds%n", (System.nanoTime() - time) / 1_000_000D);
		time = System.nanoTime();

		ExtendedMappings original;
		try (InputStream in = Files.newInputStream(mappings)) {
			original = MappingsProvider.readFullTinyMappings(in, false);
		}

		System.out.printf("Original took %.5f milliseconds%n", (System.nanoTime() - time) / 1_000_000D);
		time = System.nanoTime();

		ExtendedMappings visitor;
		try (Reader in =  new InputStreamReader(Files.newInputStream(mappings), StandardCharsets.UTF_8)) {
			visitor = TinyV2VisitorBridge.fullyRead(in, false);
		}

		System.out.printf("Visitor took %.5f milliseconds%n", (System.nanoTime() - time) / 1_000_000D);
		time = System.nanoTime();

		//The namespaces should be the same, everything else is in trouble otherwise
		assertEquals(original.getNamespaces(), visitor.getNamespaces(), "Namespaces differ");

		System.out.printf("Namespace comparison took %.5f milliseconds%n", (System.nanoTime() - time) / 1_000_000D);

		List<String> namespaces = new ArrayList<>(visitor.getNamespaces());
		String first = namespaces.get(0);

		time = System.nanoTime();

		Map<String, ClassEntry> originalClasses = original.getClassEntries().stream().collect(Collectors.toMap(entry -> entry.get(first), Function.identity()));
		Map<String, ClassEntry> visitedClasses = visitor.getClassEntries().stream().collect(Collectors.toMap(entry -> entry.get(first), Function.identity()));

		assertEquals(originalClasses.keySet(), visitedClasses.keySet(), "First namespace classes differ");
		for (Entry<String, ClassEntry> entry : originalClasses.entrySet()) {
			ClassEntry originalClass = entry.getValue();
			ClassEntry visitedClass = visitedClasses.get(entry.getKey());

			for (String namespace : namespaces.subList(1, namespaces.size())) {
				assertEquals(originalClass.get(namespace), visitedClass.get(namespace), namespace + " namespace classes differ for " + entry.getKey());
			}
		}

		System.out.printf("Class comparison took %.5f milliseconds%n", (System.nanoTime() - time) / 1_000_000D);
		time = System.nanoTime();

		Map<EntryTriple, MethodEntry> originalMethods = original.getMethodEntries().stream().collect(Collectors.toMap(entry -> entry.get(first), Function.identity()));
		Map<EntryTriple, MethodEntry> visitedMethods = visitor.getMethodEntries().stream().collect(Collectors.toMap(entry -> entry.get(first), Function.identity()));

		assertEquals(originalMethods.keySet(), visitedMethods.keySet(), "First namespace methods differ");
		for (Entry<EntryTriple, MethodEntry> entry : originalMethods.entrySet()) {
			MethodEntry originalMethod = entry.getValue();
			MethodEntry visitedMethod = visitedMethods.get(entry.getKey());

			for (String namespace : namespaces.subList(1, namespaces.size())) {
				assertEquals(originalMethod.get(namespace), visitedMethod.get(namespace), namespace + " namespace methods differ for " + entry.getKey());
			}
		}

		System.out.printf("Method comparison took %.5f milliseconds%n", (System.nanoTime() - time) / 1_000_000D);
		time = System.nanoTime();

		Map<EntryTriple, FieldEntry> originalFields = original.getFieldEntries().stream().collect(Collectors.toMap(entry -> entry.get(first), Function.identity()));
		Map<EntryTriple, FieldEntry> visitedFields = visitor.getFieldEntries().stream().collect(Collectors.toMap(entry -> entry.get(first), Function.identity()));

		assertEquals(originalFields.keySet(), visitedFields.keySet(), "First namespace fields differ");
		for (Entry<EntryTriple, FieldEntry> entry : originalFields.entrySet()) {
			FieldEntry originalField = entry.getValue();
			FieldEntry visitedField = visitedFields.get(entry.getKey());

			for (String namespace : namespaces.subList(1, namespaces.size())) {
				assertEquals(originalField.get(namespace), visitedField.get(namespace), namespace + " namespace fields differ for " + entry.getKey());
			}
		}

		System.out.printf("Field comparison took %.5f milliseconds%n", (System.nanoTime() - time) / 1_000_000D);
	}
}