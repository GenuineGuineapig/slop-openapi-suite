package dev.openapifn.fn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LazyTest {

    @Test
    void evaluatesOnceAndCaches() {
        var calls = new AtomicInteger();
        var lazy = Lazy.of(calls::incrementAndGet);
        assertThat(lazy.isEvaluated()).isFalse();
        assertThat(lazy.get()).isEqualTo(1);
        assertThat(lazy.get()).isEqualTo(1);
        assertThat(lazy.isEvaluated()).isTrue();
        assertThat(lazy.map(v -> v + 1).get()).isEqualTo(2);
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsNulls() {
        assertThatThrownBy(() -> Lazy.of(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Lazy.of(() -> null).get()).isInstanceOf(NullPointerException.class);
    }
}
