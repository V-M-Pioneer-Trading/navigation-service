package de.vnm.navigation;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.introspection.TestCenter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Writes {@code openapi.json} in the repository root from the whole application context,
 * through MockMvc: no external network (the stub auth-service binds a loopback port), a throwaway SQLite file, and the stub auth-service
 * every web test uses (the startup route audit runs exactly as in production).
 *
 * <p>Not a test of anything. It is tagged {@code openapi}, which {@code ./gradlew test}
 * excludes and {@code ./gradlew openapi} runs alone; CI runs the latter and fails on any
 * {@code git diff} of the file.
 *
 * <p>The bytes must not depend on the machine: springdoc sorts every map by key
 * ({@code springdoc.writer-with-order-by-keys}), the server is pinned to {@code /} by
 * {@code OpenApiConfig}, and the file is re-printed here with LF line endings (Jackson's
 * default pretty printer uses the platform separator) and a trailing newline.
 */
@Tag("openapi")
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecWriter {

    private static final Path DATABASE = temporaryDatabase();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        TestCenter.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void writeSpec() throws Exception {
        String served = mvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode spec = mapper.readTree(served);
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withSeparators(Separators.createDefaultInstance()
                        .withObjectFieldValueSpacing(Separators.Spacing.AFTER))
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        String json = mapper.writer(printer).writeValueAsString(spec) + "\n";

        Path target = Path.of(System.getProperty("openapi.output", "openapi.json"));
        Files.writeString(target, json, StandardCharsets.UTF_8);
    }

    private static Path temporaryDatabase() {
        try {
            Path directory = Files.createTempDirectory("navigation-service-openapi");
            directory.toFile().deleteOnExit();
            Path database = directory.resolve("nav.db");
            database.toFile().deleteOnExit();
            return database;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
