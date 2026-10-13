package dev.openapifn.codegen.cli;

import dev.openapifn.codegen.GeneratedFile;
import dev.openapifn.codegen.Generator;
import dev.openapifn.codegen.GeneratorConfig;
import dev.openapifn.parse.OpenApiLoader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Command line generator.
 *
 * <pre>
 * java -jar openapi-fn-codegen.jar --spec api.yaml --name petstore --package com.example.petstore \
 *     --output build/generated/sources --test-output build/generated/test-sources --resources-output build/generated/resources \
 *     [--base-path /api] [--no-server] [--no-client] [--no-tests]
 * </pre>
 */
public final class Main {

    private static final String USAGE = "Usage: --spec <file> --name <name> --package <package> --output <dir> [--test-output <dir>] "
        + "[--resources-output <dir>] [--base-path <path>] [--no-server] [--no-client] [--no-tests]";

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /** Runs the generator; returns the process exit code. */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        var options = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            var arg = args[i];
            if (arg.startsWith("--no-")) {
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
        var api = OpenApiLoader.defaults().load(Path.of(options.get("--spec")));
        if (api.isErr()) {
            api.failure().orElseThrow().forEach(err::println);
            return 1;
        }
        api.toOptional().orElseThrow().warnings().forEach(warning -> err.println(warning));
        var config = config(options);
        var files = Generator.generate(api.toOptional().orElseThrow(), config);
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
            .withTests(!options.containsKey("--no-tests"));
        return options.containsKey("--base-path") ? config.withBasePath(options.get("--base-path")) : config;
    }
}
