package net.fabricmc.mappings.visitor;

public interface MethodVisitor {
	ParameterVisitor visitParameter(long offset, String[] names, int localVariableIndex);

	LocalVisitor visitLocalVariable(long offset, String[] names, int localVariableIndex, int localVariableStartOffset, int localVariableTableIndex);

	void visitComment(String line);
}