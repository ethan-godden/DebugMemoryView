package com.github.ethangodden.debugmemoryview.model;

public record NullValue() implements ValueModel {

    public static final NullValue INSTANCE = new NullValue();
}
