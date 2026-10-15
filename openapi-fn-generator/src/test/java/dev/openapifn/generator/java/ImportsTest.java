package dev.openapifn.generator.java;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.generator.java.TypeRef.ClassName;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImportsTest {

    private static final ClassName NULLABLE = ClassName.of("org.jspecify.annotations", "Nullable");
    private static final ClassName OWN = ClassName.of("com.example", "Own");

    @Test
    void importsFreeSimpleNames() {
        var imports = new Imports(OWN, Set.of());
        assertThat(imports.ref(TypeRef.LIST.of(TypeRef.STRING))).isEqualTo("List<String>");
        assertThat(imports.ref(ClassName.of("com.example", "Sibling"))).isEqualTo("Sibling");
        assertThat(imports.ref(OWN.nested("Inner"), NULLABLE)).isEqualTo("Own.@Nullable Inner");
        assertThat(imports.ref(ClassName.of("com.other", "Outer", "Inner"))).isEqualTo("Outer.Inner");
        assertThat(imports.declarations()).isEqualTo("""
            import com.other.Outer;
            import java.util.List;
            import org.jspecify.annotations.Nullable;""");
    }

    @Test
    void qualifiesTakenAndShadowedNames() {
        var imports = new Imports(OWN, Set.of("Address"));
        assertThat(imports.ref(ClassName.of("java.util", "Date"))).isEqualTo("Date");
        assertThat(imports.ref(ClassName.of("java.sql", "Date"))).isEqualTo("java.sql.Date");
        assertThat(imports.ref(ClassName.of("com.example.model", "Address"), NULLABLE)).isEqualTo("com.example.model.@Nullable Address");
        assertThat(imports.ref(ClassName.of("com.other", "Own"))).isEqualTo("com.other.Own");
        assertThat(imports.declarations()).isEqualTo("import java.util.Date;\nimport org.jspecify.annotations.Nullable;");
    }

    @Test
    void rendersOtherTypes() {
        var imports = new Imports(OWN, Set.of());
        assertThat(imports.ref(new TypeRef.Primitive("int"))).isEqualTo("int");
        assertThat(imports.ref(TypeRef.BYTES)).isEqualTo("byte[]");
        assertThat(imports.ref(TypeRef.BYTES, NULLABLE)).isEqualTo("byte @Nullable []");
        assertThat(imports.ref(TypeRef.MAP.of(TypeRef.STRING, TypeRef.OBJECT), NULLABLE)).isEqualTo("@Nullable Map<String, Object>");
    }
}
