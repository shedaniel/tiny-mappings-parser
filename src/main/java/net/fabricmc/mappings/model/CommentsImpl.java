/*
 * Copyright 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *	 http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.mappings.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.fabricmc.mappings.model.CommentEntry.Class;
import net.fabricmc.mappings.model.CommentEntry.Field;
import net.fabricmc.mappings.model.CommentEntry.Method;
import net.fabricmc.mappings.model.CommentEntry.Parameter;
import net.fabricmc.mappings.model.CommentEntry.LocalVariableComment;

public class CommentsImpl implements Comments {
	private final List<Class> classComments;
	private final List<Field> fieldComments;
	private final List<Method> methodComments;
	private final List<Parameter> methodParameterComments;
	private final List<LocalVariableComment> localVariableComments;

	public CommentsImpl() {
		this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
	}

	public CommentsImpl(List<Class> classComments, List<Field> fieldComments, List<Method> methodComments, List<Parameter> methodParameterComments, List<LocalVariableComment> localVariableComments) {
		this.classComments = classComments;
		this.fieldComments = fieldComments;
		this.methodComments = methodComments;
		this.methodParameterComments = methodParameterComments;
		this.localVariableComments = localVariableComments;
	}

	@Override
	public Collection<Class> getClassComments() {
		return classComments;
	}

	@Override
	public Collection<Field> getFieldComments() {
		return fieldComments;
	}

	@Override
	public Collection<Method> getMethodComments() {
		return methodComments;
	}

	@Override
	public Collection<Parameter> getMethodParameterComments() {
		return methodParameterComments;
	}

	@Override
	public Collection<LocalVariableComment> getLocalVariableComments() {
		return localVariableComments;
	}
}