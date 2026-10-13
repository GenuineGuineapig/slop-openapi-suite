package dev.openapifn.codegen.api;

import dev.openapifn.codegen.java.Names;
import dev.openapifn.codegen.java.TypeRef.ClassName;

/**
 * Names of the generated top-level API classes.
 *
 * @param apiName the API name ({@code openapi-fn.specs.<apiName>})
 * @param basePackage the base package
 * @param basePath the default path controllers are mapped to
 */
public record GeneratorNames(String apiName, String basePackage, String basePath) {

    public String apiPackage() {
        return basePackage + ".api";
    }

    public String modelPackage() {
        return basePackage + ".model";
    }

    public ClassName api(String group) {
        return ClassName.of(apiPackage(), group + "Api");
    }

    public ClassName controller(String group) {
        return ClassName.of(apiPackage(), group + "Controller");
    }

    public ClassName client(String group) {
        return ClassName.of(apiPackage(), group + "Client");
    }

    public ClassName controllerTest(String group) {
        return ClassName.of(apiPackage(), group + "ControllerTest");
    }

    public ClassName spec() {
        return ClassName.of(basePackage, Names.pascal(apiName) + "Spec");
    }

    public ClassName clientsConfiguration() {
        return ClassName.of(basePackage, Names.pascal(apiName) + "Clients");
    }
}
