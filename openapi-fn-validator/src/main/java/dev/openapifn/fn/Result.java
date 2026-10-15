package dev.openapifn.fn;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The outcome of a computation that either succeeded with a value ({@link Ok}) or failed with an
 * error ({@link Err}). Neither side may be {@code null}.
 *
 * @param <T> success type
 * @param <E> error type
 */
public sealed interface Result<T, E> {

    /** A successful result. */
    record Ok<T, E>(T value) implements Result<T, E> {
        public Ok {
            Objects.requireNonNull(value, "value");
        }
    }

    /** A failed result. */
    record Err<T, E>(E error) implements Result<T, E> {
        public Err {
            Objects.requireNonNull(error, "error");
        }
    }

    /** A computation that may throw a checked exception. */
    @FunctionalInterface
    interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    static <T, E> Result<T, E> ok(T value) {
        return new Ok<>(value);
    }

    static <T, E> Result<T, E> err(E error) {
        return new Err<>(error);
    }

    /** Lifts an {@link Optional}: present values become {@code Ok}, absence becomes the supplied error. */
    static <T, E> Result<T, E> fromOptional(Optional<T> optional, Supplier<? extends E> error) {
        return optional.<Result<T, E>>map(Result::ok).orElseGet(() -> err(error.get()));
    }

    /** Runs {@code supplier}, capturing any exception it throws as an error via {@code onError}. */
    static <T, E> Result<T, E> attempt(ThrowingSupplier<? extends T> supplier, Function<? super Exception, ? extends E> onError) {
        try {
            return ok(supplier.get());
        } catch (Exception e) {
            return err(onError.apply(e));
        }
    }

    /** Fail-fast conversion of many results into one: the first error wins. */
    static <T, E> Result<List<T>, E> sequence(Collection<? extends Result<? extends T, E>> results) {
        var values = new ArrayList<T>(results.size());
        for (var result : results) {
            switch (result) {
                case Ok<? extends T, E> ok -> values.add(ok.value());
                case Err<? extends T, E> err -> {
                    return err(err.error());
                }
            }
        }
        return ok(List.copyOf(values));
    }

    /** Maps every element with {@code f} and {@link #sequence sequences} the results. */
    static <A, T, E> Result<List<T>, E> traverse(Collection<? extends A> items, Function<? super A, ? extends Result<? extends T, E>> f) {
        return sequence(items.stream().<Result<? extends T, E>>map(f).toList());
    }

    /** Accumulating conversion: collects all errors when at least one result failed. */
    static <T, E> Result<List<T>, List<E>> collect(Collection<? extends Result<? extends T, ? extends E>> results) {
        var values = new ArrayList<T>(results.size());
        var errors = new ArrayList<E>();
        for (var result : results) {
            switch (result) {
                case Ok<? extends T, ? extends E> ok -> values.add(ok.value());
                case Err<? extends T, ? extends E> err -> errors.add(err.error());
            }
        }
        return errors.isEmpty() ? ok(List.copyOf(values)) : err(List.copyOf(errors));
    }

    default boolean isOk() {
        return this instanceof Ok;
    }

    default boolean isErr() {
        return this instanceof Err;
    }

    default <R> R fold(Function<? super T, ? extends R> onOk, Function<? super E, ? extends R> onErr) {
        return switch (this) {
            case Ok<T, E> ok -> onOk.apply(ok.value());
            case Err<T, E> err -> onErr.apply(err.error());
        };
    }

    default <U> Result<U, E> map(Function<? super T, ? extends U> f) {
        return fold(value -> ok(f.apply(value)), Result::err);
    }

    default <F> Result<T, F> mapError(Function<? super E, ? extends F> f) {
        return fold(Result::ok, error -> err(f.apply(error)));
    }

    @SuppressWarnings("unchecked")
    default <U> Result<U, E> flatMap(Function<? super T, ? extends Result<? extends U, E>> f) {
        return fold(value -> (Result<U, E>) f.apply(value), Result::err);
    }

    /** Combines two successes with {@code f}; the first error wins. */
    default <U, R> Result<R, E> zip(Result<? extends U, E> other, BiFunction<? super T, ? super U, ? extends R> f) {
        return flatMap(left -> other.map(right -> f.apply(left, right)));
    }

    /** Keeps a success only if it satisfies {@code predicate}; otherwise fails with the computed error. */
    default Result<T, E> filter(Predicate<? super T> predicate, Function<? super T, ? extends E> error) {
        return flatMap(value -> predicate.test(value) ? ok(value) : err(error.apply(value)));
    }

    /** Replaces an error with the result of {@code f}. */
    @SuppressWarnings("unchecked")
    default Result<T, E> recover(Function<? super E, ? extends Result<? extends T, E>> f) {
        return fold(Result::ok, error -> (Result<T, E>) f.apply(error));
    }

    default Result<T, E> peek(Consumer<? super T> action) {
        if (this instanceof Ok<T, E>(var value)) {
            action.accept(value);
        }
        return this;
    }

    default Result<T, E> peekError(Consumer<? super E> action) {
        if (this instanceof Err<T, E>(var error)) {
            action.accept(error);
        }
        return this;
    }

    default T orElse(T fallback) {
        return fold(Function.identity(), error -> fallback);
    }

    default T orElseGet(Function<? super E, ? extends T> fallback) {
        return fold(Function.identity(), fallback);
    }

    default <X extends Throwable> T orElseThrow(Function<? super E, ? extends X> exception) throws X {
        if (this instanceof Err<T, E>(var error)) {
            throw exception.apply(error);
        }
        return ((Ok<T, E>) this).value();
    }

    default Optional<T> toOptional() {
        return fold(Optional::of, error -> Optional.empty());
    }

    default Optional<E> failure() {
        return fold(value -> Optional.empty(), Optional::of);
    }

    default Stream<T> stream() {
        return toOptional().stream();
    }
}
