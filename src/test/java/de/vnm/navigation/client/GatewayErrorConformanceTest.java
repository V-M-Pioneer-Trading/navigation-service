package de.vnm.navigation.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.exception.ApiException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

/**
 * The shared upstream-error contract, driven from the vendored fixtures.
 *
 * <p>Every service that calls SpaceTraders through st-gateway must answer these
 * conditions identically — see meta's {@code docs/design/upstream-errors.md}. The cases
 * live in {@code src/test/resources/gateway-errors.json}, a verbatim copy of
 * {@code meta/fixtures/gateway-errors.json}; change meta first, then re-copy.
 *
 * <p>A generated test per case rather than one loop, so a failure names the condition
 * that broke instead of the first one that did.
 */
class GatewayErrorConformanceTest {

    private static final String BASE = "https://gateway.test/proxy";
    private static final String URL = BASE + "/systems/X1-FQ86/waypoints/X1-FQ86-B29";

    @TestFactory
    List<DynamicTest> answersEveryGatewayConditionTheContractNames() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode fixtures;
        try (InputStream in = getClass().getResourceAsStream("/gateway-errors.json")) {
            assertThat(in).as("vendored fixtures on the test classpath").isNotNull();
            fixtures = mapper.readTree(in);
        }

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode testCase : fixtures.path("cases")) {
            tests.add(DynamicTest.dynamicTest(testCase.path("name").asText(), () -> runCase(testCase, mapper)));
        }
        // A silently empty fixture file would make this whole class pass on nothing.
        assertThat(tests).hasSizeGreaterThanOrEqualTo(10);
        return tests;
    }

    private void runCase(JsonNode testCase, ObjectMapper mapper) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SpaceTradersClient client = new SpaceTradersClient(builder, mapper, BASE);
        respondWith(server, testCase.path("gateway"));

        JsonNode expect = testCase.path("expect");
        assertThatThrownBy(() -> client.fetchWaypoint("X1-FQ86", "X1-FQ86-B29"))
                .isInstanceOf(ApiException.class)
                .satisfies(thrown -> assertRelayed((ApiException) thrown, expect));
        server.verify();
    }

    private void respondWith(MockRestServiceServer server, JsonNode gateway) {
        if ("no-response".equals(gateway.path("transport").asText(null))) {
            server.expect(requestTo(URL)).andRespond(request -> {
                throw new IOException("Connection refused");
            });
            return;
        }

        HttpStatusCode status = HttpStatusCode.valueOf(gateway.path("status").asInt());
        String body = gateway.has("bodyRepeat")
                ? gateway.path("bodyRepeat").path("chunk").asText().repeat(gateway.path("bodyRepeat").path("times").asInt())
                : gateway.path("body").asText();

        HttpHeaders headers = new HttpHeaders();
        gateway.path("headers").fields().forEachRemaining(e -> headers.add(e.getKey(), e.getValue().asText()));

        server.expect(requestTo(URL))
              .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).headers(headers).body(body));
    }

    /**
     * Every assertion key the fixtures may use. An unrecognised one fails the case rather
     * than being skipped: when meta adds a key, a copy that does not understand it would
     * otherwise degrade silently to a status-only test and go on reporting green — a
     * conformance suite that stops conforming without saying so.
     */
    private static final Set<String> KNOWN_EXPECTATIONS =
            Set.of("status", "message", "messageContains", "messageNotEmpty", "messageMaxLength", "headers");

    private void assertRelayed(ApiException thrown, JsonNode expect) {
        List<String> unknown = new ArrayList<>();
        expect.fieldNames().forEachRemaining(name -> {
            if (!KNOWN_EXPECTATIONS.contains(name)) unknown.add(name);
        });
        assertThat(unknown).as("expectation keys this test knows how to check").isEmpty();

        assertThat(expect.hasNonNull("status")).as("every case asserts a status").isTrue();
        assertThat(thrown.getStatus().value()).as("status").isEqualTo(expect.path("status").asInt());

        String message = thrown.getMessage();
        if (expect.hasNonNull("message")) {
            // Exact: the caller needs the upstream's own sentence unaltered, so that
            // matching on it downstream means the same thing whoever relayed it.
            assertThat(message).as("message").isEqualTo(expect.path("message").asText());
        }
        if (expect.hasNonNull("messageContains")) {
            assertThat(message).as("message").contains(expect.path("messageContains").asText());
        }
        if (expect.path("messageNotEmpty").asBoolean(false)) {
            assertThat(message).as("message").isNotBlank();
        }
        if (expect.hasNonNull("messageMaxLength")) {
            assertThat(message).as("message length").hasSizeLessThanOrEqualTo(expect.path("messageMaxLength").asInt());
        }

        if (expect.has("headers")) {
            Map<String, String> relayed = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            relayed.putAll(thrown.getHeaders());
            expect.path("headers").fields().forEachRemaining(e ->
                    assertThat(relayed)
                            .as("relayed header " + e.getKey().toLowerCase(Locale.ROOT))
                            .containsEntry(e.getKey(), e.getValue().asText()));
        }
    }
}
