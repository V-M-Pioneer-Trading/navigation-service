package de.vnm.navigation.config;

import de.vnm.navigation.introspection.AccessPolicy;
import de.vnm.navigation.introspection.IntrospectionClient;
import de.vnm.navigation.introspection.IntrospectionSettings;
import de.vnm.navigation.introspection.web.DeclarationAudit;
import de.vnm.navigation.introspection.web.IntrospectionInterceptor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Wires the introspection client from {@code AUTH_INTROSPECTION_URL} and
 * {@code AUTH_INTROSPECTION_SECRET} (auth-design.md decision 21) and installs its
 * interceptor on every handler mapping, with no path patterns: a guard scoped to a prefix is
 * a guard a new route can be added outside of.
 *
 * <p>Both values are read <b>raw</b>, by their environment names, without Spring's
 * {@code ${…}} placeholder resolution. A secret is opaque bytes: resolved, a secret
 * containing {@code ${nope}} failed startup with an exception message quoting the whole
 * secret, and one containing {@code ${SERVER_PORT}} started with a silently different
 * secret. Either variable empty refuses to start; there is no auth-optional mode. The
 * {@code CLERK_*} variables the deployment may still carry until meta#80 step 10 are read by
 * nothing.
 *
 * <p>A {@link WebMvcConfigurer} on purpose: {@code @WebMvcTest} slices include every
 * {@code WebMvcConfigurer}, so no controller test can run without the guard in front of it.
 * That closes the trap the old {@code ClerkConfig} left, where a slice that forgot to import
 * it saw no filter at all and every auth assertion passed vacuously.
 */
@Configuration
public class IntrospectionConfig implements WebMvcConfigurer {

    private final ConfigurableEnvironment environment;
    private final ApplicationContext context;

    public IntrospectionConfig(ConfigurableEnvironment environment, ApplicationContext context) {
        this.environment = environment;
        this.context = context;
    }

    /** Closed by Spring on shutdown, which releases its connection pool. */
    @Bean
    public IntrospectionClient introspectionClient() {
        return new IntrospectionClient(IntrospectionSettings.of(
                raw(environment, IntrospectionSettings.ENV_URL),
                raw(environment, IntrospectionSettings.ENV_SECRET)));
    }

    /**
     * The interceptor looks up the request mappings lazily, per {@code OPTIONS} request, so it
     * sees them only once they are all registered.
     */
    @Bean
    public IntrospectionInterceptor introspectionInterceptor() {
        return new IntrospectionInterceptor(new AccessPolicy(introspectionClient()),
                () -> context.getBeansOfType(RequestMappingHandlerMapping.class).values());
    }

    /** Runs once every singleton exists, and fails the startup if any handler is undeclared. */
    @Bean
    public DeclarationAudit declarationAudit(ApplicationContext context) {
        return new DeclarationAudit(context);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(introspectionInterceptor());
    }

    /**
     * The value of {@code name} in the first property source that has it — the environment
     * in production, a test's registration in tests — exactly as that source holds it, with
     * no placeholder resolution. {@code null} when no source has it.
     */
    static String raw(ConfigurableEnvironment environment, String name) {
        for (PropertySource<?> source : environment.getPropertySources()) {
            Object value = source.getProperty(name);
            if (value != null) {
                return value.toString();
            }
        }
        return null;
    }
}
