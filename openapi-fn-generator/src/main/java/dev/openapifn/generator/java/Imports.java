package dev.openapifn.generator.java;

import dev.openapifn.generator.java.TypeRef.ArrayOf;
import dev.openapifn.generator.java.TypeRef.ClassName;
import dev.openapifn.generator.java.TypeRef.Parameterized;
import dev.openapifn.generator.java.TypeRef.Primitive;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Tracks the imports of one compilation unit and renders type references: top-level classes are
 * referenced by simple name when that name is free (imported if needed) and qualified otherwise.
 * Type-use annotations are placed where Java requires them, e.g. {@code Outer.@Nullable Inner}.
 */
public final class Imports {

    private final String packageName;
    private final String ownTopLevel;
    private final Set<String> declared;
    private final Map<String, String> bySimpleName = new HashMap<>();

    /**
     * @param ownClass the top-level class of the compilation unit
     * @param declared simple names of the types nested in the unit, which imports must not shadow
     */
    public Imports(ClassName ownClass, Set<String> declared) {
        this.packageName = ownClass.packageName();
        this.ownTopLevel = ownClass.simpleNames().getFirst();
        this.declared = Set.copyOf(declared);
        bySimpleName.put(ownTopLevel, ownClass.topLevel().canonicalName());
    }

    public String ref(TypeRef type) {
        return ref(type, null);
    }

    /** {@code type} with an optional type-use annotation (such as {@code Nullable}), which is imported. */
    public String ref(TypeRef type, ClassName annotation) {
        var prefix = annotation == null ? "" : "@" + ref(annotation) + " ";
        return switch (type) {
            case ClassName name -> qualify(name, prefix);
            case Parameterized parameterized -> qualify(parameterized.raw(), prefix) + parameterized.arguments().stream()
                .map(this::ref).collect(Collectors.joining(", ", "<", ">"));
            case Primitive primitive -> primitive.keyword();
            case TypeRef.Variable variable -> prefix + variable.name();
            case ArrayOf array -> ref(array.component()) + (annotation == null ? "" : " " + prefix.trim() + " ") + "[]";
        };
    }

    private String qualify(ClassName name, String annotation) {
        var names = name.simpleNames();
        var head = names.subList(0, names.size() - 1);
        var path = (simple(name) ? "" : name.packageName() + ".") + (head.isEmpty() ? "" : String.join(".", head) + ".");
        return path + annotation + name.simpleName();
    }

    private boolean simple(ClassName name) {
        var top = name.simpleNames().getFirst();
        var canonical = name.packageName() + "." + top;
        if (name.packageName().equals(packageName) && top.equals(ownTopLevel)) {
            return true;
        }
        if (declared.contains(top)) {
            return false;
        }
        var existing = bySimpleName.putIfAbsent(top, canonical);
        return existing == null || existing.equals(canonical);
    }

    /** The import declarations, sorted, excluding {@code java.lang} and the unit's own package. */
    public String declarations() {
        var lines = new TreeSet<String>();
        bySimpleName.values().forEach(canonical -> {
            var pkg = canonical.substring(0, canonical.lastIndexOf('.'));
            if (!pkg.equals("java.lang") && !pkg.equals(packageName)) {
                lines.add("import " + canonical + ";");
            }
        });
        return String.join("\n", lines);
    }
}
