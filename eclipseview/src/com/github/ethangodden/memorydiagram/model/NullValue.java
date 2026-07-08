package com.github.ethangodden.memorydiagram.model;

public record NullValue() implements ValueModel {

    public static final NullValue INSTANCE = new NullValue();
}
