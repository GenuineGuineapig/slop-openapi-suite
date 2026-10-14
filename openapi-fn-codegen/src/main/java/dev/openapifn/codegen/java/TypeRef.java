package dev.openapifn.codegen.java;

import java.util.List;

/** A reference to a Java type in generated code. */
public sealed interface TypeRef {

    /**
     * A class, possibly nested: {@code simpleNames} lists the enclosing classes first.
     *
     * @param packageName the package
     * @param simpleNames top-level name followed by nested names
     */
    record ClassName(String packageName, List<String> simpleNames) implements TypeRef {

        public ClassName {
            simpleNames = List.copyOf(simpleNames);
        }

        public static ClassName of(String packageName, String... simpleNames) {
            return new ClassName(packageName, List.of(simpleNames));
        }

        public ClassName nested(String name) {
            var names = new java.util.ArrayList<>(simpleNames);
            names.add(name);
            return new ClassName(packageName, names);
        }

        public String simpleName() {
            return simpleNames.getLast();
        }

        public ClassName topLevel() {
            return new ClassName(packageName, List.of(simpleNames.getFirst()));
        }

        public boolean isNested() {
            return simpleNames.size() > 1;
        }

        /** The name relative to the package, e.g. {@code Outer.Inner}. */
        public String relativeName() {
            return String.join(".", simpleNames);
        }

        public String canonicalName() {
            return packageName + "." + relativeName();
        }

        public TypeRef of(TypeRef... arguments) {
            return new Parameterized(this, List.of(arguments));
        }
    }

    /**
     * A generic type.
     *
     * @param raw the generic class
     * @param arguments type arguments
     */
    record Parameterized(ClassName raw, List<TypeRef> arguments) implements TypeRef {
        public Parameterized {
            arguments = List.copyOf(arguments);
        }
    }

    /**
     * A type variable such as {@code T}.
     *
     * @param name the variable name
     */
    record Variable(String name) implements TypeRef {
    }

    /** A primitive type such as {@code int}. */
    record Primitive(String keyword) implements TypeRef {
    }

    /** An array type. */
    record ArrayOf(TypeRef component) implements TypeRef {
    }

    ClassName OBJECT = ClassName.of("java.lang", "Object");
    ClassName STRING = ClassName.of("java.lang", "String");
    ClassName INTEGER = ClassName.of("java.lang", "Integer");
    ClassName LONG = ClassName.of("java.lang", "Long");
    ClassName BOOLEAN = ClassName.of("java.lang", "Boolean");
    ClassName FLOAT = ClassName.of("java.lang", "Float");
    ClassName DOUBLE = ClassName.of("java.lang", "Double");
    ClassName BIG_DECIMAL = ClassName.of("java.math", "BigDecimal");
    ClassName BIG_INTEGER = ClassName.of("java.math", "BigInteger");
    ClassName LIST = ClassName.of("java.util", "List");
    ClassName SET = ClassName.of("java.util", "Set");
    ClassName MAP = ClassName.of("java.util", "Map");
    ClassName STREAM = ClassName.of("java.util.stream", "Stream");
    ClassName FLUX = ClassName.of("reactor.core.publisher", "Flux");
    ClassName MONO = ClassName.of("reactor.core.publisher", "Mono");
    ClassName LOCAL_DATE = ClassName.of("java.time", "LocalDate");
    ClassName OFFSET_DATE_TIME = ClassName.of("java.time", "OffsetDateTime");
    ClassName OFFSET_TIME = ClassName.of("java.time", "OffsetTime");
    ClassName DURATION = ClassName.of("java.time", "Duration");
    ClassName UUID = ClassName.of("java.util", "UUID");
    ClassName URI = ClassName.of("java.net", "URI");
    Primitive BYTE_ARRAY_COMPONENT = new Primitive("byte");
    ArrayOf BYTES = new ArrayOf(BYTE_ARRAY_COMPONENT);
}
