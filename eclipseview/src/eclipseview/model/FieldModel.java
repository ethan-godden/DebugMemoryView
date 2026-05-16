package eclipseview.model;

public record FieldModel(String name, String declaringTypeName, String declaredTypeName, ValueModel value) {

    /** Diff key within an object/class; declaring type disambiguates shadowed fields. */
    public String fieldKey() {
        return declaringTypeName + "." + name;
    }
}
