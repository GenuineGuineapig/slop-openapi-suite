package dev.openapifn.generator.cli;

import dev.openapifn.fn.Result;
import dev.openapifn.generator.GeneratedFile;
import dev.openapifn.generator.Generator;
import dev.openapifn.generator.GeneratorConfig;
import dev.openapifn.load.ResourceLoaders;
import dev.openapifn.parse.OpenApiDocument;
import dev.openapifn.parse.OpenApiLoader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Command line generator.
 *
 * <pre>
 * java -jar openapi-fn-generator.jar --spec api.yaml --name petstore --package com.example.petstore \
 *     --output build/generated/sources --test-output build/generated/test-sources --resources-output build/generated/resources \
 *     [--base-path /api] [--no-server] [--no-client] [--no-tests] [--reactive]
 * </pre>
 */
public final class Main {

    private static final String USAGE = "Usage: --spec <file> --name <name> --package <package> --output <dir> [--test-output <dir>] "
        + "[--resources-output <dir>] [--base-path <path>] [--no-server] [--no-client] [--no-tests] [--reactive]";

    /** Ends the process with an exit code; replaced in tests, which must not end the test JVM. */
    static IntConsumer exit = System::exit;

    private Main() {
    }

    public static void main(String[] args) {
        exit.accept(run(args, System.out, System.err));
    }

    /** Runs the generator; returns the process exit code. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        var options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            if (arg.startsWith("--no-") || arg.equals("--reactive")) {
                options.put(arg, "true");
            } else if (arg.startsWith("--") && i + 1 < args.length) {
                options.put(arg, args[++i]);
            } else {
                err.println("Unexpected argument '" + arg + "'\n" + USAGE);
                return 2;
            }
        }
        for (var required : new String[] {"--spec", "--name", "--package", "--output"}) {
            if (!options.containsKey(required)) {
                err.println("Missing " + required + "\n" + USAGE);
                return 2;
            }
        }
        return switch (new OpenApiLoader(ResourceLoaders.withRemote(Duration.ofSeconds(30))).load(Path.of(options.get("--spec")))) {
            case Result.Err(var diagnostics) -> {
                diagnostics.forEach(err::println);
                yield 1;
            }
            case Result.Ok(var api) -> generate(api, options, out, err);
        };
    }

    private static int generate(OpenApiDocument api, Map<String, String> options, PrintStream out, PrintStream err) {
        api.warnings().forEach(err::println);
        var config = config(options);
        var files = Generator.generate(api, config);
        var output = Path.of(options.get("--output"));
        try {
            GeneratedFile.write(files, output, Path.of(options.getOrDefault("--test-output", output.toString())),
                Path.of(options.getOrDefault("--resources-output", output.toString())));
        } catch (IOException e) {
            err.println("Cannot write generated files: " + e.getMessage());
            return 1;
        }
        out.println("Generated " + files.size() + " files for '" + config.name() + "'");
        return 0;
    }

    private static GeneratorConfig config(Map<String, String> options) {
        var config = GeneratorConfig.of(options.get("--name"), options.get("--package"))
            .withServer(!options.containsKey("--no-server"))
            .withClient(!options.containsKey("--no-client"))
            .withTests(!options.containsKey("--no-tests"))
            .withReactive(options.containsKey("--reactive"));
        return options.containsKey("--base-path") ? config.withBasePath(options.get("--base-path")) : config;
    }
}
