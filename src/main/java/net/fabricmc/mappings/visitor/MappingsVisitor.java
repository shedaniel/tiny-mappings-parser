package net.fabricmc.mappings.visitor;

public interface MappingsVisitor {
	void visitVersion(int major, int minor);

	void visitNamespaces(String... namespaces);

	void visitProperty(String name);

	void visitProperty(String name, String value);

	ClassVisitor visitClass(long offset, String[] names);

	/**
	 * Finish visiting the mapping file
	 * 
	 * <p>Nothing else will be called after this.
	 */
	default void finish() {
	}
}