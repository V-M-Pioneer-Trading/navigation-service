package de.vnm.navigation.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Comparator;
import java.util.List;

/**
 * What {@code /api-docs} says about itself, fixed so the committed {@code openapi.json} is the
 * same bytes on every machine.
 *
 * <ul>
 *   <li>The one server is {@code /}, relative. Left alone, springdoc writes the URL of whatever
 *       request first reached {@code /api-docs} and caches it: a random port in a test, and
 *       in production the internal host that request came in on, which no consumer behind
 *       CloudFront can reach anyway. {@code /} resolves against wherever the document was
 *       fetched from, which is what Swagger UI needs.</li>
 *   <li>Tags are sorted by name. springdoc lists them in handler registration order, which
 *       nothing promises; every map in the document is already sorted by key
 *       ({@code springdoc.writer-with-order-by-keys}).</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI navigationOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("navigation-service")
                        .description("Read-through cache for SpaceTraders waypoint, market and shipyard data")
                        .version("v1"))
                .servers(List.of(new Server().url("/")));
    }

    @Bean
    public OpenApiCustomizer sortedTags() {
        return openApi -> {
            List<Tag> tags = openApi.getTags();
            if (tags != null) {
                tags.sort(Comparator.comparing(Tag::getName));
            }
        };
    }
}
