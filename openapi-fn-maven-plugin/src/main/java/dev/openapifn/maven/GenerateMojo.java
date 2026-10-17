package dev.openapifn.maven;

import dev.openapifn.fn.Result;
import dev.openapifn.generator.GeneratedFile;
import dev.openapifn.generator.Generator;
import dev.openapifn.generator.GeneratorConfig;
import dev.openapifn.json.JsonWriter;
import dev.openapifn.load.Diagnostic;
import dev.openapifn.load.ResourceLoader;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.parse.OpenApiLoader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.maven.model.Resource;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

/**
 * Generates models, controllers, clients and controller tests from one OpenAPI description and
 * adds them to the build: the sources and test sources to be compiled, the bundled description
 * (with every document it references) to the resources. Configure one execution per description:
 *
 * <pre>{@code
 * <plugin>
 *     <groupId>dev.openapifn</groupId>
 *     <artifactId>openapi-fn-maven-plugin</artifactId>
 *     <version>...</version>
 *     <executions>
 *         <execution>
 *             <id>petstore</id>
 *             <goals><goal>generate</goal></goals>
 *             <configuration>
 *                 <spec>src/main/openapi/petstore.yaml</spec>
 *                 <packageName>com.example.petstore</packageName>
 *             </configuration>
 *         </execution>
 *     </executions>
 * </plugin>
 * }</pre>
 *
 * <p>Generation is skipped while its inputs are unchanged: the content of the documents the
 * description loads, the options and the plugin version. Remote documents are cached between builds
 * and fetched again once older than {@link #remoteMaxAge}; {@code -Dopenapi-fn.refresh} fetches them
 * now, offline builds ({@code -o}) use the cached copies.
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class GenerateMojo extends AbstractMojo {

    /** The root document; the documents it references are loaded relative to it. */
    @Parameter(required = true)
    File spec;

    /**
     * The name of the API, which identifies it at runtime ({@code openapi-fn.specs.<name>.*}
     * properties); by default the execution's id.
     */
    @Parameter(defaultValue = "${mojoExecution.executionId}", required = true)
    String name;

    /** The base package: models go to {@code <package>.model}, API types to {@code <package>.api}. */
    @Parameter(required = true)
    String packageName;

    /** The path controllers are mapped to, if it differs from the path of the first server URL. */
    @Parameter
    String basePath;

    /** Whether to generate API interfaces and controllers. */
    @Parameter(defaultValue = "true")
    boolean server = true;

    /** Whether to generate clients. */
    @Parameter(defaultValue = "true")
    boolean client = true;

    /** Whether to generate controller tests (which requires {@link #server}). */
    @Parameter(defaultValue = "true")
    boolean tests = true;

    /**
     * Whether to generate for Spring WebFlux: {@code Mono} and {@code Flux} in interfaces, reactive
     * controllers, {@code WebClient} clients and {@code WebTestClient} tests.
     */
    @Parameter(defaultValue = "false")
    boolean reactive;

    /** How long cached remote documents are used before they are fetched again (an ISO-8601 duration). */
    @Parameter(defaultValue = "PT24H")
    String remoteMaxAge = "PT24H";

    /** Whether to fetch remote documents now, whatever the age of their cached copies. */
    @Parameter(property = "openapi-fn.refresh", defaultValue = "false")
    boolean refresh;

    /** Whether the build is offline: only cached remote documents are used. */
    @Parameter(defaultValue = "${settings.offline}", readonly = true)
    boolean offline;

    /** Where remote documents are cached, shared by builds. */
    @Parameter(defaultValue = "${settings.localRepository}/.cache/openapi-fn/remote")
    File remoteCache;

    /** The directory generated sources go to, in a directory per API. */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/openapi-fn")
    File outputDirectory;

    /** The directory generated test sources go to, in a directory per API. */
    @Parameter(defaultValue = "${project.build.directory}/generated-test-sources/openapi-fn")
    File testOutputDirectory;

    /** The directory generated resources go to, in a directory per API. */
    @Parameter(defaultValue = "${project.build.directory}/generated-resources/openapi-fn")
    File resourcesOutputDirectory;

    /** Whether to skip generation. */
    @Parameter(property = "openapi-fn.skip", defaultValue = "false")
    boolean skip;

    @Parameter(defaultValue = "${plugin.version}", readonly = true)
    String pluginVersion;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("openapi-fn: skipping '" + name + "'");
            return;
        }
        var sources = outputDirectory.toPath().resolve(name);
        var testSources = testOutputDirectory.toPath().resolve(name);
        var resources = resourcesOutputDirectory.toPath().resolve(name);
        var location = spec.toPath();
        var loader = loader(remoteCache.toPath(), refresh ? Duration.ZERO : Duration.parse(remoteMaxAge), offline);
        var api = new OpenApiLoader(loader).load(location)
            .orElseThrow(diagnostics -> new MojoFailureException("Invalid OpenAPI description " + location + ":\n" + describe(diagnostics)));
        var stamp = Path.of(project.getBuild().getDirectory(), "openapi-fn", name + ".inputs");
        var inputs = inputs(api);
        try {
            var upToDate = Files.exists(stamp) && Files.readString(stamp).equals(inputs)
                && Stream.of(sources, testSources, resources).allMatch(Files::isDirectory);
            if (upToDate) {
                getLog().info("openapi-fn: '" + name + "' is up to date");
            } else {
                generate(api, sources, testSources, resources);
                Files.createDirectories(stamp.getParent());
                Files.writeString(stamp, inputs);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot generate code for '" + name + "': " + e, e);
        }
        project.addCompileSourceRoot(sources.toString());
        project.addTestCompileSourceRoot(testSources.toString());
        var resource = new Resource();
        resource.setDirectory(resources.toString());
        project.addResource(resource);
    }

    private void generate(OpenApiDocument api, Path sources, Path testSources, Path resources) throws IOException {
        api.warnings().forEach(warning -> getLog().warn("openapi-fn: " + warning));
        var config = GeneratorConfig.of(name, packageName).withServer(server).withClient(client).withTests(tests).withReactive(reactive);
        var files = Generator.generate(api, Optional.ofNullable(basePath).map(config::withBasePath).orElse(config));
        for (var directory : List.of(sources, testSources, resources)) {
            delete(directory);
            Files.createDirectories(directory);
        }
        GeneratedFile.write(files, sources, testSources, resources);
        getLog().info("openapi-fn: generated " + files.size() + " files for '" + name + "'");
    }

    /**
     * What generation depends on, as text: the plugin version, the options, and every document the
     * description loads (local and remote) by its URI and a digest of its content.
     */
    String inputs(OpenApiDocument api) {
        var options = Stream.of("plugin=" + pluginVersion, "name=" + name, "package=" + packageName, "basePath=" + basePath, "server=" + server,
            "client=" + client, "tests=" + tests, "reactive=" + reactive);
        var documents = new TreeMap<String, String>();
        api.schemas().documents().forEach((uri, document) -> documents.put(uri.toString(), digest(JsonWriter.compact(document.root()))));
        return Stream.concat(options, documents.entrySet().stream().map(document -> document.getKey() + "=" + document.getValue()))
            .collect(Collectors.joining("\n", "", "\n"));
    }

    /** Loads descriptions with remote documents cached in {@code cache}. */
    static ResourceLoader loader(Path cache, Duration maxAge, boolean offline) {
        return ResourceLoaders.withCachedRemote(Duration.ofSeconds(30), cache, maxAge, offline);
    }

    private static String digest(String content) {
        // Every Java platform has SHA-256.
        var sha256 = Result.attempt(() -> MessageDigest.getInstance("SHA-256"), Exception::getMessage).orElseThrow(IllegalStateException::new);
        return HexFormat.of().formatHex(sha256.digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    /** Deletes {@code directory} with everything in it, if it exists. */
    private static void delete(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String describe(List<Diagnostic> diagnostics) {
        return diagnostics.stream().map(diagnostic -> "  " + diagnostic).collect(Collectors.joining("\n"));
    }
}
