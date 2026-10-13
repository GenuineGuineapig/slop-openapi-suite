package dev.openapifn.fn;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A thread-safe memoizing supplier: the computation runs at most once (unless it throws) and its
 * value is cached. The supplier must not return {@code null}.
 */
public final class Lazy<T> implements Supplier<T> {

    private final Supplier<? extends T> supplier;
    private T value;

    private Lazy(Supplier<? extends T> supplier) {
        this.supplier = supplier;
    }

    public static <T> Lazy<T> of(Supplier<? extends T> supplier) {
        return new Lazy<>(Objects.requireNonNull(supplier, "supplier"));
    }

    @Override
    public synchronized T get() {
        if (value == null) {
            value = Objects.requireNonNull(supplier.get(), "Lazy supplier returned null");
        }
        return value;
    }

    public <U> Lazy<U> map(Function<? super T, ? extends U> f) {
        return of(() -> f.apply(get()));
    }

    public synchronized boolean isEvaluated() {
        return value != null;
    }
}
