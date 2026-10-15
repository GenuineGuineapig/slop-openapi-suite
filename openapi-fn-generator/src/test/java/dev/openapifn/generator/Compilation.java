package dev.openapifn.generator;

import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.parse.OpenApiLoader;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/** Generates code into a directory and compiles it against the test class path. */
final class Compilation {

    final Path root;
    final List<GeneratedFile> files;
    final String diagnostics;
    final boolean success;
    final URLClassLoader classLoader;

    private Compilation(Path root, List<GeneratedFile> files, String diagnostics, boolean success, URLClassLoader classLoader) {
        this.root = root;
        this.files = files;
        this.diagnostics = diagnostics;
        this.success = success;
        this.classLoader = classLoader;
    }

    static OpenApiDocument load(String classpathLocation) {
        return OpenApiLoader.defaults().load(classpathLocation).orElseThrow(d -> new AssertionError(d.toString()));
    }

    /** Generates and compiles; {@code extraSources} maps relative paths to additional hand-written sources. */
    static Compilation of(OpenApiDocument api, GeneratorConfig config, Path root, java.util.Map<String, String> extraSources) {
        try {
            var files = Generator.generate(api, config);
            if (System.getenv("KEEP") != null) {
                GeneratedFile.write(files, Path.of(System.getenv("KEEP"), config.name()), Path.of(System.getenv("KEEP"), config.name()),
                    Path.of(System.getenv("KEEP"), config.name()));
            }
            var sources = root.resolve("src");
            var tests = root.resolve("test");
            var resources = root.resolve("resources");
            GeneratedFile.write(files, sources, tests, resources);
            for (var extra : extraSources.entrySet()) {
                var target = sources.resolve(extra.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, extra.getValue());
            }
            var classes = root.resolve("classes");
            Files.createDirectories(classes);
            Files.createDirectories(resources);
            List<String> javaFiles;
            try (var walk = Stream.concat(Files.walk(sources), Files.exists(tests) ? Files.walk(tests) : Stream.empty())) {
                javaFiles = walk.filter(path -> path.toString().endsWith(".java")).map(Path::toString).toList();
            }
            var options = List.of("-d", classes.toString(), "-classpath", System.getProperty("java.class.path"), "-parameters",
                "-proc:none", "-Xlint:all,-serial,-processing,-deprecation,-classfile", "-encoding", "UTF-8");
            var output = new StringWriter();
            var compiler = ToolProvider.getSystemJavaCompiler();
            var task = compiler.getTask(output, null, null, options, null,
                compiler.getStandardFileManager(null, null, null).getJavaFileObjectsFromStrings(javaFiles));
            var success = task.call();
            var loader = new URLClassLoader(new URL[] {classes.toUri().toURL(), resources.toUri().toURL()}, Compilation.class.getClassLoader());
            return new Compilation(root, files, output.toString(), success, loader);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    Class<?> loadClass(String className) {
        try {
            return classLoader.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    String file(String path) {
        return files.stream().filter(f -> f.path().equals(path)).findFirst().orElseThrow(() -> new AssertionError("No file " + path)).content();
    }
}
