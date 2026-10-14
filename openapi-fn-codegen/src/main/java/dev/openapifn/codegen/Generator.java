package dev.openapifn.codegen;

import dev.openapifn.codegen.api.ApiRenderer;
import dev.openapifn.codegen.api.GeneratorNames;
import dev.openapifn.codegen.api.OperationModel;
import dev.openapifn.codegen.api.TestRenderer;
import dev.openapifn.codegen.java.Names;
import dev.openapifn.codegen.java.TypeRef;
import dev.openapifn.codegen.java.TypeRef.ClassName;
import dev.openapifn.codegen.model.ModelRenderer;
import dev.openapifn.codegen.model.TypeMapper;
import dev.openapifn.parse.OpenApiDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates Java sources, tests and resources from an API description. A pure function of the
 * description and the configuration.
 */
public final class Generator {

    private Generator() {
    }

    public static List<GeneratedFile> generate(OpenApiDocument api, GeneratorConfig config) {
        var servers = api.model().servers();
        var serverUrl = servers.getFirst().defaultUrl();
        var defaultBasePath = basePath(serverUrl);
        var names = new GeneratorNames(config.name(), config.basePackage(), config.basePath().orElse(defaultBasePath));
        var mapper = new TypeMapper(api, names.modelPackage());
        mapper.defineComponents();
        var taken = new HashSet<String>();
        var stream = config.reactive() ? TypeRef.FLUX : TypeRef.STREAM;
        var groups = new LinkedHashMap<String, List<OperationModel>>();
        for (var operation : api.operations()) {
            var model = OperationModel.of(operation, names.apiPackage(), mapper, taken, stream);
            groups.computeIfAbsent(model.group(), group -> new ArrayList<>()).add(model);
        }
        // Webhooks and callbacks are sent by the API provider: they get receiving controllers and sending clients of their own.
        var groupNames = new HashSet<>(groups.keySet());
        // Operations of one webhook, or of one callback URL, are received at one URL when they differ by method.
        var urls = new LinkedHashMap<List<String>, List<OperationModel>>();
        var webhooks = Names.unique("Webhooks", groupNames);
        for (var operation : api.webhooks()) {
            var model = OperationModel.of(operation, operation.displayName(), webhooks, operation.method().toLowerCase(Locale.ROOT) + " "
                + operation.path(), names.apiPackage(), mapper, taken, stream);
            groups.computeIfAbsent(webhooks, group -> new ArrayList<>()).add(model);
            urls.computeIfAbsent(List.of("webhooks", operation.path()), url -> new ArrayList<>()).add(model);
        }
        var callbacks = Names.unique("Callbacks", groupNames);
        for (var callback : api.callbacks()) {
            // Named after the callback, plus the method when the callback has several operations.
            var single = api.callbacks().stream().filter(other -> other.owner().equals(callback.owner()) && other.name().equals(callback.name())).count() == 1;
            var model = OperationModel.of(callback.operation(), callback.id(), callbacks, callback.name() + (single ? "" : " "
                + callback.operation().method().toLowerCase(Locale.ROOT)), names.apiPackage(), mapper, taken, stream);
            groups.computeIfAbsent(callbacks, group -> new ArrayList<>()).add(model);
            urls.computeIfAbsent(List.of("callbacks", callback.name(), callback.owner().displayName(), callback.operation().path()), url -> new ArrayList<>())
                .add(model);
        }
        shareReceiverPaths(urls, groups, taken);
        var models = new ModelRenderer(mapper);
        var renderer = new ApiRenderer(mapper, models, names, config.reactive());
        var files = new ArrayList<GeneratedFile>();
        mapper.definitions().stream().filter(definition -> !definition.name().isNested())
            .forEach(definition -> files.add(source(definition.name(), models.file(definition))));
        for (var operations : groups.values()) {
            for (var operation : operations) {
                operation.request().ifPresent(request -> files.add(source(request, renderer.request(operation))));
                files.add(source(operation.response(), renderer.response(operation)));
            }
        }
        var receivers = config.server() ? groups.values().stream().flatMap(List::stream).filter(OperationModel::outbound)
            .map(operation -> new Bundle.Receiver(operation.method(), operation.id(), operation.receiverPath())).toList() : List.<Bundle.Receiver>of();
        var bundle = Bundle.of(config.name(), api.uri(), api.schemas().documents(), config.basePath(), receivers);
        files.addAll(bundle.files());
        files.add(source(names.spec(), renderer.spec(bundle.location(), serverUrl)));
        if (config.server()) {
            groups.forEach((group, operations) -> {
                files.add(source(names.api(group), renderer.api(group, operations)));
                files.add(source(names.controller(group), renderer.controller(group, operations)));
            });
            if (config.tests()) {
                var tests = new TestRenderer(api, names, config.reactive());
                groups.forEach((group, operations) -> files.add(new GeneratedFile(GeneratedFile.Kind.TEST_SOURCE, path(names.controllerTest(group)),
                    tests.test(group, operations))));
            }
        }
        if (config.client()) {
            groups.forEach((group, operations) -> files.add(source(names.client(group), renderer.client(group, operations))));
            files.add(source(names.clientsConfiguration(), renderer.clientsConfiguration(List.copyOf(groups.keySet()))));
        }
        return files;
    }

    /**
     * Gives the operations of one URL (a webhook, or a callback expression) one receiver path, named
     * after the webhook or callback; they come from one path item, so their methods tell them apart.
     * (Callbacks of different names sharing an expression and a method stay apart: one URL could not
     * tell them apart.)
     */
    private static void shareReceiverPaths(Map<List<String>, List<OperationModel>> urls, Map<String, List<OperationModel>> groups, java.util.Set<String> taken) {
        var names = new HashSet<>(taken);
        urls.forEach((url, operations) -> {
            if (operations.size() < 2) {
                return;
            }
            var path = "/" + url.getFirst() + "/" + Names.unique(Names.camel(url.get(1)), names);
            groups.values().forEach(models -> models.replaceAll(model -> operations.contains(model) ? model.withReceiverPath(path) : model));
        });
    }

    /** The path of a server URL, with a leading slash and without a trailing one. */
    static String basePath(String serverUrl) {
        var path = serverUrl.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]*", "").replaceFirst("\\?.*$", "");
        var normalized = path.startsWith("/") ? path : "/" + path;
        return normalized.length() > 1 && normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }

    private static GeneratedFile source(ClassName name, String content) {
        return new GeneratedFile(GeneratedFile.Kind.SOURCE, path(name), content);
    }

    private static String path(ClassName name) {
        return name.packageName().replace('.', '/') + "/" + name.simpleNames().getFirst() + ".java";
    }
}
