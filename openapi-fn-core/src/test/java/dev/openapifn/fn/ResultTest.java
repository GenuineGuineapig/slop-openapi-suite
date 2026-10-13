package dev.openapifn.fn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResultTest {

    private static final Result<Integer, String> OK = Result.ok(2);
    private static final Result<Integer, String> ERR = Result.err("boom");

    @Test
    void constructorsRejectNull() {
        assertThatThrownBy(() -> Result.ok(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Result.err(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void foldAndPredicates() {
        assertThat(OK.isOk()).isTrue();
        assertThat(OK.isErr()).isFalse();
        assertThat(ERR.isOk()).isFalse();
        assertThat(ERR.isErr()).isTrue();
        assertThat(OK.<String>fold(v -> "ok" + v, e -> "err" + e)).isEqualTo("ok2");
        assertThat(ERR.<String>fold(v -> "ok" + v, e -> "err" + e)).isEqualTo("errboom");
    }

    @Test
    void mapping() {
        assertThat(OK.map(v -> v * 10)).isEqualTo(Result.ok(20));
        assertThat(ERR.map(v -> v * 10)).isEqualTo(ERR);
        assertThat(OK.mapError(String::length)).isEqualTo(Result.ok(2));
        assertThat(ERR.mapError(String::length)).isEqualTo(Result.err(4));
        assertThat(OK.flatMap(v -> Result.ok("v" + v))).isEqualTo(Result.ok("v2"));
        assertThat(OK.flatMap(v -> Result.err("nope"))).isEqualTo(Result.err("nope"));
        assertThat(ERR.flatMap(v -> Result.ok("v" + v))).isEqualTo(ERR);
        assertThat(OK.zip(Result.ok(3), Integer::sum)).isEqualTo(Result.ok(5));
        assertThat(OK.zip(Result.<Integer, String>err("second"), Integer::sum)).isEqualTo(Result.err("second"));
        assertThat(ERR.zip(Result.ok(3), Integer::sum)).isEqualTo(ERR);
    }

    @Test
    void filterAndRecover() {
        assertThat(OK.filter(v -> v > 1, v -> "small")).isEqualTo(OK);
        assertThat(OK.filter(v -> v > 5, v -> "small " + v)).isEqualTo(Result.err("small 2"));
        assertThat(ERR.filter(v -> true, v -> "x")).isEqualTo(ERR);
        assertThat(ERR.recover(e -> Result.ok(e.length()))).isEqualTo(Result.ok(4));
        assertThat(OK.recover(e -> Result.ok(0))).isEqualTo(OK);
    }

    @Test
    void sideEffects() {
        var seen = new ArrayList<Object>();
        OK.peek(seen::add).peekError(seen::add);
        ERR.peek(seen::add).peekError(seen::add);
        assertThat(seen).containsExactly(2, "boom");
    }

    @Test
    void extraction() throws Exception {
        assertThat(OK.orElse(9)).isEqualTo(2);
        assertThat(ERR.orElse(9)).isEqualTo(9);
        assertThat(OK.orElseGet(String::length)).isEqualTo(2);
        assertThat(ERR.orElseGet(String::length)).isEqualTo(4);
        assertThat(OK.orElseThrow(IOException::new)).isEqualTo(2);
        assertThatThrownBy(() -> ERR.orElseThrow(IOException::new)).isInstanceOf(IOException.class).hasMessage("boom");
        assertThat(OK.toOptional()).contains(2);
        assertThat(ERR.toOptional()).isEmpty();
        assertThat(OK.failure()).isEmpty();
        assertThat(ERR.failure()).contains("boom");
        assertThat(OK.stream()).containsExactly(2);
        assertThat(ERR.stream()).isEmpty();
    }

    @Test
    void factories() {
        assertThat(Result.fromOptional(Optional.of(1), () -> "none")).isEqualTo(Result.ok(1));
        assertThat(Result.fromOptional(Optional.empty(), () -> "none")).isEqualTo(Result.err("none"));
        assertThat(Result.attempt(() -> 1, Exception::getMessage)).isEqualTo(Result.ok(1));
        assertThat(Result.<Integer, String>attempt(() -> {
            throw new IOException("io");
        }, Exception::getMessage)).isEqualTo(Result.err("io"));
    }

    @Test
    void sequencing() {
        assertThat(Result.sequence(List.of(OK, Result.ok(3)))).isEqualTo(Result.ok(List.of(2, 3)));
        assertThat(Result.sequence(List.of(OK, ERR, Result.err("later")))).isEqualTo(Result.err("boom"));
        assertThat(Result.<String, Integer, String>traverse(List.of("a", "bb"), s -> Result.ok(s.length())))
            .isEqualTo(Result.ok(List.of(1, 2)));
        assertThat(Result.collect(List.of(OK, Result.ok(3)))).isEqualTo(Result.ok(List.of(2, 3)));
        assertThat(Result.collect(List.of(OK, ERR, Result.err("later")))).isEqualTo(Result.err(List.of("boom", "later")));
    }
}
