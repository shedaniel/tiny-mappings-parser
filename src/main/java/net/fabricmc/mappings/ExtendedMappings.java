package net.fabricmc.mappings;

import java.util.Collection;

import net.fabricmc.mappings.model.Comments;
import net.fabricmc.mappings.model.LocalVariableEntry;
import net.fabricmc.mappings.model.MethodParameterEntry;

public interface ExtendedMappings extends Mappings {
	Collection<? extends MethodParameterEntry> getMethodParameterEntries();
	Collection<? extends LocalVariableEntry> getLocalVariableEntries();
	Comments getComments();

	default boolean isExtended() {
		return !getMethodParameterEntries().isEmpty() || !getLocalVariableEntries().isEmpty() || !getComments().isEmpty();
	}

	static ExtendedMappings createEmpty() {
		return wrap(DummyMappings.INSTANCE);
	}

	static ExtendedMappings wrap(Mappings normal) {
		return new ExtendedWrapper(normal);
	}
}