package dev.openapifn.gradle;

import dev.openapifn.codegen.java.Names;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

/**
 * Generates code from OpenAPI descriptions as part of the build. Each spec registered in the
 * {@code openapiFn} extension gets a {@code generate<Name>OpenApi} task whose outputs are added to
 * the main and test source sets and the main resources (so compilation depends on generation);
 * {@code generateOpenApi} runs them all.
 */
public final class OpenApiFnPlugin implements Plugin<Project> {

    public static final String EXTENSION = "openapiFn";
    public static final String GENERATE_ALL = "generateOpenApi";
    public static final String GROUP = "openapi-fn";

    @Override
    public void apply(Project project) {
        var extension = project.getExtensions().create(EXTENSION, OpenApiFnExtension.class);
        extension.getAddDependencies().convention(true);
        extension.getReactive().convention(false);
        var all = project.getTasks().register(GENERATE_ALL, task -> {
            task.setGroup(GROUP);
            task.setDescription("Generates code from all OpenAPI descriptions");
        });
        extension.getSpecs().all(spec -> {
            spec.getServer().convention(true);
            spec.getClient().convention(true);
            spec.getTests().convention(true);
            spec.getReactive().convention(extension.getReactive());
            var task = project.getTasks().register(taskName(spec), GenerateOpenApi.class, generate -> configure(project, generate, spec));
            all.configure(generateAll -> generateAll.dependsOn(task));
            project.getPluginManager().withPlugin("java", java -> addToSourceSets(project, task));
        });
        project.getPluginManager().withPlugin("java", java -> addDependencies(project, extension));
    }

    /** {@code generate<Name>OpenApi}. */
    static String taskName(OpenApiSpec spec) {
        return "generate" + Names.pascal(spec.getName()) + "OpenApi";
    }

    private static void configure(Project project, GenerateOpenApi task, OpenApiSpec spec) {
        task.setGroup(GROUP);
        task.setDescription("Generates code from the OpenAPI description '" + spec.getName() + "'");
        task.getSpec().set(spec.getSpec());
        task.getReferences().from(spec.getSpec().map(file -> file.getAsFile().getParentFile()));
        task.getApiName().set(spec.getName());
        task.getPackageName().set(spec.getPackageName());
        task.getBasePath().set(spec.getBasePath());
        task.getServer().set(spec.getServer());
        task.getClient().set(spec.getClient());
        task.getTests().set(spec.getTests());
        task.getReactive().set(spec.getReactive());
        var output = project.getLayout().getBuildDirectory().dir("generated/openapi-fn/" + spec.getName());
        task.getSourcesDir().convention(output.map(directory -> directory.dir("java")));
        task.getTestSourcesDir().convention(output.map(directory -> directory.dir("test-java")));
        task.getResourcesDir().convention(output.map(directory -> directory.dir("resources")));
        // Remote documents are not tracked inputs: fetch them again on every build.
        task.getOutputs().upToDateWhen(OpenApiFnPlugin::withoutRemoteDocuments);
        task.getOutputs().cacheIf("the bundle has no remote documents", OpenApiFnPlugin::withoutRemoteDocuments);
    }

    private static boolean withoutRemoteDocuments(org.gradle.api.Task task) {
        return !((GenerateOpenApi) task).bundlesRemoteDocuments();
    }

    private static void addToSourceSets(Project project, TaskProvider<GenerateOpenApi> task) {
        var sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME, main -> {
            main.getJava().srcDir(task.flatMap(GenerateOpenApi::getSourcesDir));
            main.getResources().srcDir(task.flatMap(GenerateOpenApi::getResourcesDir));
        });
        sourceSets.named(SourceSet.TEST_SOURCE_SET_NAME, test -> test.getJava().srcDir(task.flatMap(GenerateOpenApi::getTestSourcesDir)));
    }

    private static void addDependencies(Project project, OpenApiFnExtension extension) {
        var dependencies = project.getDependencies();
        var version = version();
        var stack = extension.getReactive().map(reactive -> reactive ? "webflux" : "webmvc");
        project.getConfigurations().named("implementation", configuration -> configuration.getDependencies().addAllLater(
            extension.getAddDependencies().zip(stack, (add, web) -> add ? List.<Dependency>of(dependencies.create("dev.openapifn:openapi-fn-spring"
                + (web.equals("webflux") ? "-webflux" : "") + ":" + version)) : List.<Dependency>of())));
        project.getConfigurations().named("testImplementation", configuration -> configuration.getDependencies().addAllLater(
            extension.getAddDependencies().zip(stack, (add, web) -> add ? List.<Dependency>of(dependencies.create("dev.openapifn:openapi-fn-spring-test:"
                + version), dependencies.create("org.springframework.boot:spring-boot-starter-" + web + "-test")) : List.<Dependency>of())));
    }

    /** The version of openapi-fn this plugin belongs to. */
    static String version() {
        return ResourceBundle.getBundle("dev.openapifn.gradle.plugin", Locale.ROOT, OpenApiFnPlugin.class.getClassLoader()).getString("version");
    }
}
