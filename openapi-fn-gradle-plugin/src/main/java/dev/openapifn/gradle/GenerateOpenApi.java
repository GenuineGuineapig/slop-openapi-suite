package dev.openapifn.gradle;

import dev.openapifn.generator.GeneratedFile;
import dev.openapifn.generator.Generator;
import dev.openapifn.generator.GeneratorConfig;
import dev.openapifn.load.Diagnostic;
import dev.openapifn.load.ResourceLoader;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Generates sources, test sources and resources from one API description. Remote references are
 * fetched and bundled. They are cached ({@link #getRemoteCache()}) and fetched again once they are
 * older than {@link #getRemoteMaxAge()}; their content is an input through
 * {@link #getRemoteFingerprint()}, so the task is up to date (or taken from the build cache) while
 * they are unchanged.
 */
@CacheableTask
public abstract class GenerateOpenApi extends DefaultTask {

    /** The root document. */
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getSpec();

    /** Documents the root document may reference; by default its directory. */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getReferences();

    @Input
    public abstract Property<String> getApiName();

    /**
     * A digest of the remote documents the description references (empty when there are none),
     * fetched to compute it; see {@link #fingerprint}.
     */
    @Input
    public abstract Property<String> getRemoteFingerprint();

    /** Where remote documents are cached, shared by builds (the Gradle user home's {@code caches/openapi-fn/remote}). */
    @Internal
    public abstract DirectoryProperty getRemoteCache();

    /** How long cached remote documents are used before they are fetched again. */
    @Internal
    public abstract Property<Duration> getRemoteMaxAge();

    /** Whether only cached remote documents are used (Gradle's {@code --offline}). */
    @Internal
    public abstract Property<Boolean> getOffline();

    @Input
    public abstract Property<String> getPackageName();

    @Input
    @Optional
    public abstract Property<String> getBasePath();

    @Input
    public abstract Property<Boolean> getServer();

    @Input
    public abstract Property<Boolean> getClient();

    @Input
    public abstract Property<Boolean> getTests();

    @Input
    public abstract Property<Boolean> getReactive();

    @OutputDirectory
    public abstract DirectoryProperty getSourcesDir();

    @OutputDirectory
    public abstract DirectoryProperty getTestSourcesDir();

    @OutputDirectory
    public abstract DirectoryProperty getResourcesDir();

    @Inject
    protected abstract FileSystemOperations getFileSystemOperations();

    /** Loads descriptions with remote documents cached in {@code cache}. */
    public static ResourceLoader loader(Path cache, Duration maxAge, boolean offline) {
        return ResourceLoaders.withCachedRemote(Duration.ofSeconds(30), cache, maxAge, offline);
    }

    /**
     * A digest of the remote ({@code http} and {@code https}) documents of the description at
     * {@code spec}, loaded with {@code loader}; empty when it references none, or cannot be loaded
     * (generation then reports why).
     */
    public static String fingerprint(Path spec, ResourceLoader loader) {
        var loaded = new OpenApiLoader(loader).load(spec);
        if (loaded.isErr()) {
            return "";
        }
        var remote = new TreeMap<String, String>();
        loaded.toOptional().orElseThrow().schemas().documents().forEach((uri, document) -> {
            if (Set.of("http", "https").contains(uri.getScheme())) {
                remote.put(uri.toString(), dev.openapifn.json.JsonWriter.compact(document.root()));
            }
        });
        if (remote.isEmpty()) {
            return "";
        }
        var content = new StringBuilder();
        remote.forEach((uri, json) -> content.append(uri).append('\n').append(json).append('\n'));
        // A 128-bit (MD5) digest: enough to tell a change.
        return UUID.nameUUIDFromBytes(content.toString().getBytes(StandardCharsets.UTF_8)).toString();
    }

    @TaskAction
    public void generate() throws IOException {
        var location = getSpec().get().getAsFile().toPath();
        var loaded = new OpenApiLoader(loader(getRemoteCache().get().getAsFile().toPath(), getRemoteMaxAge().get(), getOffline().get())).load(location);
        var api = loaded.toOptional().orElseThrow(() -> new GradleException("Invalid OpenAPI description " + location + ":\n"
            + describe(loaded.failure().orElseThrow())));
        api.warnings().forEach(warning -> getLogger().warn("openapi-fn: {}", warning));
        var config = GeneratorConfig.of(getApiName().get(), getPackageName().get())
            .withServer(getServer().get())
            .withClient(getClient().get())
            .withTests(getTests().get())
            .withReactive(getReactive().get());
        var files = Generator.generate(api, getBasePath().map(config::withBasePath).getOrElse(config));
        var sources = getSourcesDir().get().getAsFile().toPath();
        var tests = getTestSourcesDir().get().getAsFile().toPath();
        var resources = getResourcesDir().get().getAsFile().toPath();
        getFileSystemOperations().delete(spec -> spec.delete(sources, tests, resources));
        for (var directory : List.of(sources, tests, resources)) {
            Files.createDirectories(directory);
        }
        GeneratedFile.write(files, sources, tests, resources);
        getLogger().info("openapi-fn: generated {} files for '{}'", files.size(), getApiName().get());
    }

    private static String describe(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(diagnostic -> "  " + diagnostic).collect(Collectors.joining("\n"));
    }
}
