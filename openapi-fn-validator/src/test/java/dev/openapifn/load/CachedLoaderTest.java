package dev.openapifn.load;

import static org.assertj.core.api.Assertions.assertThat;

import dev.openapifn.fn.Result;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CachedLoaderTest {

    private static final URI DOC = URI.create("https://schemas.example.com/pet.yaml");

    @TempDir
    Path cache;

    private final List<URI> fetched = new ArrayList<>();
    private final AtomicReference<Result<String, String>> answer = new AtomicReference<>(Result.ok("type: object"));
    private final ResourceLoader remote = uri -> {
        fetched.add(uri);
        return answer.get();
    };

    /** A clock that only moves when told. */
    private static final class Manual extends Clock {
        Instant now = Instant.now();

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void documentsAreFetchedOnceWhileTheyAreFresh() {
        var clock = new Manual();
        var loader = ResourceLoaders.cached(remote, cache, Duration.ofHours(24), false, clock);
        assertThat(loader.load(DOC).toOptional()).contains("type: object");
        assertThat(loader.load(DOC).toOptional()).contains("type: object");
        assertThat(fetched).hasSize(1);
        clock.now = clock.now.plus(Duration.ofDays(2));
        answer.set(Result.ok("type: string"));
        assertThat(loader.load(DOC).toOptional()).as("stale: fetched again").contains("type: string");
        assertThat(fetched).hasSize(2);
    }

    @Test
    void offlineBuildsUseTheCache() {
        var clock = new Manual();
        ResourceLoaders.cached(remote, cache, Duration.ofHours(24), false, clock).load(DOC);
        clock.now = clock.now.plus(Duration.ofDays(30));
        var offline = ResourceLoaders.cached(remote, cache, Duration.ofHours(24), true, clock);
        assertThat(offline.load(DOC).toOptional()).contains("type: object");
        assertThat(offline.load(URI.create("https://schemas.example.com/other.yaml")).failure()).hasValueSatisfying(reason ->
            assertThat(reason).startsWith("Cannot fetch https://schemas.example.com/other.yaml offline: it is not cached in "));
        assertThat(fetched).hasSize(1);
    }

    @Test
    void documentsThatCannotBeFetchedAgainComeFromTheCache() {
        var clock = new Manual();
        var loader = ResourceLoaders.cached(remote, cache, Duration.ofMinutes(1), false, clock);
        loader.load(DOC);
        clock.now = clock.now.plus(Duration.ofHours(1));
        answer.set(Result.err("Cannot fetch: HTTP 503"));
        assertThat(loader.load(DOC).toOptional()).contains("type: object");
        assertThat(loader.load(URI.create("https://schemas.example.com/new.yaml")).failure()).contains("Cannot fetch: HTTP 503");
        assertThat(fetched).hasSize(3);
    }

    @Test
    void cachingIsBestEffort() throws IOException {
        var blocked = Files.writeString(cache.resolve("file"), "not a directory");
        var loader = ResourceLoaders.cached(remote, blocked, Duration.ofHours(1), false, new Manual());
        assertThat(loader.load(DOC).toOptional()).contains("type: object");
        assertThat(loader.load(DOC).toOptional()).contains("type: object");
        assertThat(fetched).hasSize(2);
    }

    @Test
    void localDocumentsAreNotCached() throws IOException {
        var local = Files.writeString(cache.resolve("local.yaml"), "type: integer");
        var loader = ResourceLoaders.withCachedRemote(Duration.ofSeconds(1), cache.resolve("remote"), Duration.ofHours(1), true);
        assertThat(loader.load(local.toUri()).toOptional()).contains("type: integer");
        assertThat(loader.load(DOC).isErr()).isTrue();
    }
}
