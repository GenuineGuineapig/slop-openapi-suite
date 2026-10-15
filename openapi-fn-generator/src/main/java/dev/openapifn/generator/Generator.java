package dev.openapifn.generator;

import dev.openapifn.generator.api.ApiRenderer;
import dev.openapifn.generator.api.GeneratorNames;
import dev.openapifn.generator.api.OperationModel;
import dev.openapifn.generator.api.Relations;
import dev.openapifn.generator.api.TestRenderer;
import dev.openapifn.generator.java.Names;
import dev.openapifn.generator.java.TypeRef.ClassName;
import dev.openapifn.generator.java.TypeRef;
import dev.openapifn.generator.model.ModelRenderer;
import dev.openapifn.generator.model.TypeMapper;
import dev.openapifn.parse.OpenApiDocument;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        // Operations of one webhook, or of one callback URL, are received at one URL (and told apart by method, then by content).
        var urls = new LinkedHashMap<List<String>, List<OperationModel>>();
        var urlNames = new LinkedHashMap<List<String>, String>();
        var webhooks = Names.unique("Webhooks", groupNames);
        for (var operation : api.webhooks()) {
            var model = OperationModel.of(operation, operation.displayName(), webhooks, operation.method().toLowerCase(Locale.ROOT) + " "
                + operation.path(), names.apiPackage(), mapper, taken, stream);
            groups.computeIfAbsent(webhooks, group -> new ArrayList<>()).add(model);
            urls.computeIfAbsent(List.of("webhooks", operation.path()), url -> new ArrayList<>()).add(model);
            urlNames.putIfAbsent(List.of("webhooks", operation.path()), operation.path());
        }
        var callbacks = Names.unique("Callbacks", groupNames);
        var owners = new LinkedHashMap<String, OperationModel>();
        for (var callback : api.callbacks()) {
            // Named after the callback, plus the method when the callback has several operations.
            var single = api.callbacks().stream().filter(other -> other.owner().equals(callback.owner()) && other.name().equals(callback.name())).count() == 1;
            var model = OperationModel.of(callback.operation(), callback.id(), callbacks, callback.name() + (single ? "" : " "
                + callback.operation().method().toLowerCase(Locale.ROOT)), names.apiPackage(), mapper, taken, stream);
            groups.computeIfAbsent(callbacks, group -> new ArrayList<>()).add(model);
            owners.put(model.id(), groups.values().stream().flatMap(List::stream).filter(owner -> owner.operation().equals(callback.owner())).findFirst()
                .orElseThrow());
            // Callbacks with one URL expression are sent to one URL, whatever their names.
            var url = List.of("callbacks", callback.owner().displayName(), callback.operation().path());
            urls.computeIfAbsent(url, key -> new ArrayList<>()).add(model);
            urlNames.putIfAbsent(url, callback.name());
        }
        shareReceiverPaths(urls, urlNames, groups, taken);
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
            var all = groups.values().stream().flatMap(List::stream).toList();
            var relations = new Relations(link -> api.target(link).flatMap(target -> all.stream().filter(model -> model.operation().equals(target)).findFirst()),
                owners);
            groups.forEach((group, operations) -> files.add(source(names.client(group), renderer.client(group, operations, relations))));
            files.add(source(names.clientsConfiguration(), renderer.clientsConfiguration(List.copyOf(groups.keySet()))));
        }
        return files;
    }

    /**
     * Gives the operations of one URL (a webhook, or a callback expression of one operation) one
     * receiver path, named after the (first) webhook or callback: a subscriber registers one URL.
     * Their methods tell them apart, and the requests of operations with one method their content.
     */
    private static void shareReceiverPaths(Map<List<String>, List<OperationModel>> urls, Map<List<String>, String> urlNames,
                                           Map<String, List<OperationModel>> groups, Set<String> taken) {
        var chosen = new HashSet<String>();
        urls.forEach((url, operations) -> {
            if (operations.size() < 2) {
                return;
            }
            // Single receivers are received at /<kind>/<method>: avoid those of other operations, and the names of other shared paths.
            var avoided = new HashSet<>(taken);
            operations.forEach(operation -> avoided.remove(operation.method()));
            avoided.addAll(chosen);
            var name = Names.unique(Names.camel(urlNames.get(url)), avoided);
            chosen.add(name);
            var path = "/" + url.getFirst() + "/" + name;
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
