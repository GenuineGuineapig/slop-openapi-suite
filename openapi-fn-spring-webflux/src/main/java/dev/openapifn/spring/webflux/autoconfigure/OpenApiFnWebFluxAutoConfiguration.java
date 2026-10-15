package dev.openapifn.spring.webflux.autoconfigure;

import dev.openapifn.spring.OpenApiRuntimes;
import dev.openapifn.spring.autoconfigure.OpenApiFnAutoConfiguration;
import dev.openapifn.spring.autoconfigure.OpenApiFnProperties;
import dev.openapifn.spring.webflux.ExchangeCredentials;
import dev.openapifn.spring.webflux.OpenApiReactiveExceptionHandler;
import dev.openapifn.spring.webflux.OpenApiValidationWebFilter;
import dev.openapifn.spring.webflux.ReactiveSpringSecurityCredentials;
import dev.openapifn.spring.webflux.client.OpenApiWebClients;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Spring WebFlux support: in reactive web applications the validation web filter and
 * problem-detail error handling; everywhere the factory of reactive clients.
 */
@AutoConfiguration(after = OpenApiFnAutoConfiguration.class)
public class OpenApiFnWebFluxAutoConfiguration {

    /** Reactive web applications: the validation web filter and problem details for binding failures. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    static class Reactive {

        @Bean
        @ConditionalOnMissingBean(name = "openApiValidationWebFilter")
        OpenApiValidationWebFilter openApiValidationWebFilter(OpenApiRuntimes runtimes, OpenApiFnProperties properties,
                                                              ObjectProvider<ExchangeCredentials> credentials) {
            var validation = properties.validation();
            return new OpenApiValidationWebFilter(runtimes, new OpenApiValidationWebFilter.Settings(validation.request().enabled(),
                validation.response().enabled(), validation.response().mode(), validation.rejectUnknownPaths(), validation.excludePaths(),
                validation.maxBodySize().toBytes(), validation.filterOrder()), credentials.getIfAvailable(() -> ExchangeCredentials.NONE));
        }

        @Bean
        @ConditionalOnMissingBean
        OpenApiReactiveExceptionHandler openApiReactiveExceptionHandler() {
            return new OpenApiReactiveExceptionHandler();
        }
    }

    /** WebFlux with Spring Security: credentials are verified by what it authenticated the exchange as. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(name = "org.springframework.security.core.context.ReactiveSecurityContextHolder")
    @ConditionalOnProperty(prefix = "openapi-fn.security", name = "spring-security", matchIfMissing = true)
    static class ReactiveSpringSecurity {

        @Bean
        @ConditionalOnMissingBean
        ExchangeCredentials springSecurityExchangeCredentials() {
            return ReactiveSpringSecurityCredentials.exchangeCredentials();
        }
    }

    /** Reactive clients use the application's {@code WebClient.Builder} (a fresh clone each time) when there is one. */
    @Bean
    @ConditionalOnMissingBean
    public OpenApiWebClients openApiWebClients(OpenApiFnProperties properties, ObjectProvider<WebClient.Builder> builder,
                                               ObjectProvider<OpenApiRuntimes> runtimes, ObjectProvider<JsonMapper> mapper) {
        return new OpenApiWebClients(properties, () -> builder.getIfAvailable(WebClient::builder).clone(), runtimes::getObject,
            mapper.getIfAvailable(JsonMapper::new));
    }
}
