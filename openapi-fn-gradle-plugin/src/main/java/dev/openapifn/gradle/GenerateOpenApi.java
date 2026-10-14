package dev.openapifn.gradle;

import dev.openapifn.codegen.GeneratedFile;
import dev.openapifn.codegen.Generator;
import dev.openapifn.codegen.GeneratorConfig;
import dev.openapifn.load.Diagnostic;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
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
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Generates sources, test sources and resources from one API description. Remote references are
 * fetched and bundled. Their content is an input through {@link #getRemoteFingerprint()}: the
 * remote documents are fetched to check for changes, and the task is up to date (or taken from the
 * build cache) while they are unchanged.
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

    /**
     * A digest of the remote ({@code http} and {@code https}) documents of the description at
     * {@code spec}, fetched now; empty when it references none, or cannot be loaded (generation then
     * reports why).
     */
    public static String fingerprint(java.nio.file.Path spec) {
        var loaded = new OpenApiLoader(ResourceLoaders.withRemote(Duration.ofSeconds(30))).load(spec);
        if (loaded.isErr()) {
            return "";
        }
        var remote = new java.util.TreeMap<String, String>();
        loaded.toOptional().orElseThrow().schemas().documents().forEach((uri, document) -> {
            if (java.util.Set.of("http", "https").contains(uri.getScheme())) {
                remote.put(uri.toString(), dev.openapifn.json.JsonWriter.compact(document.root()));
            }
        });
        if (remote.isEmpty()) {
            return "";
        }
        var content = new StringBuilder();
        remote.forEach((uri, json) -> content.append(uri).append('\n').append(json).append('\n'));
        // A 128-bit (MD5) digest: enough to tell a change.
        return java.util.UUID.nameUUIDFromBytes(content.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    @TaskAction
    public void generate() throws IOException {
        var location = getSpec().get().getAsFile().toPath();
        var loaded = new OpenApiLoader(ResourceLoaders.withRemote(Duration.ofSeconds(30))).load(location);
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
