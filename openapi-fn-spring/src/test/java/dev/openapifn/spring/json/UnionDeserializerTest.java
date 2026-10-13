package dev.openapifn.spring.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.json.JsonMapper;

class UnionDeserializerTest {

    @JsonDeserialize(using = Shape.Deserializer.class)
    sealed interface Shape permits Circle, Square, Shape.Label {
        final class Deserializer extends UnionDeserializer<Shape> {
            Deserializer() {
                super(Shape.class, true, List.of(Circle.class, Square.class, Label.class));
            }
        }

        record Label(@JsonValue String value) implements Shape {
            @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
            public Label {
                Objects.requireNonNull(value, "value");
            }
        }
    }

    record Circle(double radius) implements Shape {
        Circle {
            if (radius < 0) {
                throw new IllegalArgumentException("negative");
            }
        }
    }

    record Square(double side) implements Shape {
    }

    @JsonDeserialize(using = Loose.Deserializer.class)
    sealed interface Loose permits A, B {
        final class Deserializer extends UnionDeserializer<Loose> {
            Deserializer() {
                super(Loose.class, false, List.of(A.class, B.class));
            }
        }
    }

    record A(String x) implements Loose {
    }

    record B(String x) implements Loose {
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void picksTheMatchingMember() {
        assertThat(MAPPER.readValue("{\"radius\": 2}", Shape.class)).isEqualTo(new Circle(2));
        assertThat(MAPPER.readValue("{\"side\": 3}", Shape.class)).isEqualTo(new Square(3));
        assertThat(MAPPER.readValue("\"text\"", Shape.class)).isEqualTo(new Shape.Label("text"));
        assertThat(MAPPER.writeValueAsString(new Shape.Label("text"))).isEqualTo("\"text\"");
        assertThat(MAPPER.readValue("{\"x\": \"y\"}", Loose.class)).isEqualTo(new A("y"));
    }

    @Test
    void rejectsJsonMatchingNoMember() {
        assertThatThrownBy(() -> MAPPER.readValue("{\"radius\": -1}", Shape.class)).isInstanceOf(JacksonException.class)
            .hasMessageContaining("JSON matches none of the types of Shape");
        assertThatThrownBy(() -> MAPPER.readValue("42", Shape.class)).isInstanceOf(JacksonException.class);
    }
}
