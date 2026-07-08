package com.github.ethangodden.memorydiagram.model;

import java.util.List;

/** Static fields of one class relevant to the current stack. */
public record StaticsClassModel(String className, String simpleName, List<FieldModel> fields, int fieldsOmitted) {
}
