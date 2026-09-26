package de.vnm.navigation.config;

import de.vnm.navigation.introspection.AccessPolicy;
import de.vnm.navigation.introspection.IntrospectionClient;
import de.vnm.navigation.introspection.IntrospectionSettings;
import de.vnm.navigation.introspection.web.DeclarationAudit;
import de.vnm.navigation.introspection.web.IntrospectionInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the introspection client from {@code AUTH_INTROSPECTION_URL} and
 * {@code AUTH_INTROSPECTION_SECRET} (auth-design.md decision 21) and installs its
 * interceptor on every handler mapping, with no path patterns: a guard scoped to a prefix is
 * a guard a new route can be added outside of.
 *
 * <p>Either variable empty refuses to start; there is no auth-optional mode. The
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

    private final String url;
    private final String secret;

    public IntrospectionConfig(@Value("${auth.introspection.url:}") String url,
                               @Value("${auth.introspection.secret:}") String secret) {
        this.url = url;
        this.secret = secret;
    }

    /** Closed by Spring on shutdown, which releases its connection pool. */
    @Bean
    public IntrospectionClient introspectionClient() {
        return new IntrospectionClient(IntrospectionSettings.of(url, secret));
    }

    @Bean
    public IntrospectionInterceptor introspectionInterceptor() {
        return new IntrospectionInterceptor(new AccessPolicy(introspectionClient()));
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
}
