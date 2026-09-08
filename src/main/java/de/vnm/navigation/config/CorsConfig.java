package de.vnm.navigation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Browser access to {@code /api/**}.
 *
 * <p>{@code CORS_ALLOWED_ORIGIN} takes a comma-separated list, so a deployment can serve
 * a production origin and a preview origin without a second build.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${cors.allowed-origin:http://localhost:3000}") String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST")
                .allowedHeaders(HttpHeaders.CONTENT_TYPE, HttpHeaders.AUTHORIZATION)
                // Pacing headers relayed from st-gateway. None of these is
                // CORS-safelisted, so without this the browser can see the 429
                // and not the instructions that came with it — the relay would
                // reach the network and stop at the last hop that matters.
                .exposedHeaders(HttpHeaders.RETRY_AFTER,
                        "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset");
    }
}
