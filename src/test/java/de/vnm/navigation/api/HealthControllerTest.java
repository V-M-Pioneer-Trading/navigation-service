package de.vnm.navigation.api;

import de.vnm.navigation.introspection.StubCenter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Health is {@code @IgnoreCredentials}: it must keep answering while auth-service is broken,
 * whatever the caller sends. So this slice points the application at a center that answers
 * 500 to everything and counts, and every test asserts it was never asked.
 */
@WebMvcTest(HealthController.class)
class HealthControllerTest {

    private static final StubCenter BROKEN_CENTER =
            StubCenter.answering(StubCenter.Reply.of(500, "{\"error\":{\"message\":\"down\"}}"));

    @DynamicPropertySource
    static void center(DynamicPropertyRegistry registry) {
        registry.add("AUTH_INTROSPECTION_URL", BROKEN_CENTER::url);
        registry.add("AUTH_INTROSPECTION_SECRET", () -> "health-test-secret");
    }

    @Autowired MockMvc mockMvc;

    @BeforeEach
    void resetCenter() {
        BROKEN_CENTER.resetCalls();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/health", "/api/navigation/health"})
    void answersWithoutAnyHeader(String path) throws Exception {
        mockMvc.perform(get(path))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("ok"));

        assertThat(BROKEN_CENTER.calls()).isZero();
    }

    /**
     * OPTIONS on a health path is answered by Spring's own responder, which takes the path's
     * intent: every handler there ignores credentials, so its OPTIONS does too. Regression:
     * the responder was declared {@code none}, so a bearer made it ask the center and a broken
     * center made the health path answer 503.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/health", "/api/navigation/health"})
    void optionsWithABearer_neverAsksTheCenter(String path) throws Exception {
        mockMvc.perform(options(path).header("Authorization", "Bearer x"))
               .andExpect(status().isOk());

        assertThat(BROKEN_CENTER.calls()).isZero();
    }

    /** A bearer here — valid, expired or garbage — is not read, so the broken center is never asked. */
    @ParameterizedTest
    @ValueSource(strings = {"/health", "/api/navigation/health"})
    void neverReadsTheHeaderAndNeverAsksTheCenter(String path) throws Exception {
        mockMvc.perform(get(path).header("Authorization", "Bearer garbage.that.would.be.inactive"))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.status").value("ok"));
        mockMvc.perform(head(path).header("Authorization", "Bearer a, Bearer b"))
               .andExpect(status().isOk());

        assertThat(BROKEN_CENTER.calls()).isZero();
    }
}
