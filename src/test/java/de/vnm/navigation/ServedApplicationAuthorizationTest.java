package de.vnm.navigation;

import de.vnm.navigation.introspection.TestCenter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application on a real port, spoken to over real HTTP: what MockMvc cannot show.
 *
 * <ul>
 *   <li>The application starts at all — so the startup audit found a declaration for every
 *       handler it runs, springdoc's and Spring Boot's error controller included.</li>
 *   <li>Tomcat's error dispatch: a 404 or 405 stays a 404 or 405, and is never re-decided
 *       into a 500 by the interceptor.</li>
 *   <li>Tomcat's HEAD handling, and its view of two {@code Authorization} header lines.</li>
 *   <li>The rejection body on the wire is exactly the family envelope.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ServedApplicationAuthorizationTest {

    private static final Path DATABASE = temporaryDatabase();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        TestCenter.register(registry);
    }

    @LocalServerPort int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void resetCenter() {
        TestCenter.center().resetCalls();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/health", "/api/navigation/health", "/api-docs", "/swagger-ui/index.html"})
    void healthAndDocs_ignoreCredentials_andNeverAskTheCenter(String path) throws Exception {
        HttpResponse<String> response = send(request(path).header("Authorization", "Bearer garbage").GET());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(TestCenter.center().calls()).isZero();
    }

    @Test
    void theSwaggerEntryPointRedirects_withoutAskingTheCenter() throws Exception {
        HttpResponse<String> response = send(request("/swagger-ui.html").header("Authorization", "Bearer garbage").GET());

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(TestCenter.center().calls()).isZero();
    }

    @Test
    void aRefreshWithoutAHeader_isTheExactEnvelopeOnTheWire() throws Exception {
        HttpResponse<String> response = send(request("/api/navigation/v1/waypoints/X1-FQ86-B29/refresh")
                .POST(HttpRequest.BodyPublishers.noBody()));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(response.body()).isEqualTo("{\"error\":{\"message\":\"a bearer token is required\"}}");
    }

    /** Two header lines reach the interceptor as two values: no credential, never "the first". */
    @Test
    void twoAuthorizationLines_areNoCredential() throws Exception {
        HttpResponse<String> response = send(request("/api/navigation/v1/waypoints/X1-FQ86-B29/refresh")
                .header("Authorization", TestCenter.OPERATOR_BEARER)
                .header("Authorization", TestCenter.OPERATOR_BEARER)
                .POST(HttpRequest.BodyPublishers.noBody()));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEqualTo("{\"error\":{\"message\":\"a bearer token is required\"}}");
        assertThat(TestCenter.center().calls()).isZero();
    }

    @Test
    void twoAuthorizationLines_oneOfThemEmpty_areNoCredential() throws Exception {
        HttpResponse<String> response = send(request("/api/navigation/v1/waypoints/X1-FQ86-B29/refresh")
                .header("Authorization", TestCenter.OPERATOR_BEARER)
                .header("Authorization", "")
                .POST(HttpRequest.BodyPublishers.noBody()));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).isEqualTo("{\"error\":{\"message\":\"a bearer token is required\"}}");
        assertThat(TestCenter.center().calls()).isZero();
    }

    /**
     * OPTIONS on a path whose handlers all ignore credentials ignores them too, over real
     * Tomcat, with a bearer the center would fail on. Regression: these answered 503.
     */
    @ParameterizedTest
    @ValueSource(strings = {"/health", "/api/navigation/health", "/api-docs", "/error"})
    void optionsOnAnIgnoredPath_neverAsksTheCenter(String path) throws Exception {
        HttpResponse<String> response = send(request(path).header("Authorization", TestCenter.CENTER_FAILS_BEARER)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));

        assertThat(response.statusCode()).isIn(200, 204);
        assertThat(TestCenter.center().calls()).isZero();
    }

    /** A path with a guarded handler keeps OPTIONS as {@code none}: a presented token is checked. */
    @Test
    void optionsOnAGuardedPath_stillVerifiesAPresentedToken() throws Exception {
        HttpResponse<String> response = send(request("/api/navigation/v1/waypoints/X1-FQ86-B29/refresh")
                .header("Authorization", TestCenter.CENTER_FAILS_BEARER)
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(TestCenter.center().calls()).isEqualTo(1);
    }

    /** Tomcat's error dispatch continues a request already decided; it is never re-decided as a 500. */
    @Test
    void anUnknownPath_isA404_andAnUnmappedMethod_isA405() throws Exception {
        assertThat(send(request("/api/navigation/v1/nothing-here")
                .header("Authorization", TestCenter.OPERATOR_BEARER)
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode()).isEqualTo(404);
        assertThat(send(request("/api/navigation/v1/waypoints/X1-FQ86-B29/refresh")
                .PUT(HttpRequest.BodyPublishers.noBody())).statusCode()).isEqualTo(405);
        assertThat(send(request("/health").POST(HttpRequest.BodyPublishers.noBody())).statusCode()).isEqualTo(405);

        assertThat(TestCenter.center().calls()).isZero();
    }

    /**
     * HEAD reaches the GET handler: an anonymous HEAD of an uncached waypoint is the service's
     * own "sign in to fetch it live" 401, not the guard's, and the center is not asked. With a
     * bad token it is the guard's 401, after one call.
     */
    @Test
    void headIsDispatchedToTheGetHandler() throws Exception {
        String read = "/api/navigation/v1/waypoints/X1-FQ86-B29";

        HttpResponse<String> anonymous = send(request(read).method("HEAD", HttpRequest.BodyPublishers.noBody()));
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(TestCenter.center().calls()).isZero();

        HttpResponse<String> inactive = send(request(read).header("Authorization", TestCenter.INACTIVE_BEARER)
                .method("HEAD", HttpRequest.BodyPublishers.noBody()));
        assertThat(inactive.statusCode()).isEqualTo(401);
        assertThat(inactive.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(TestCenter.center().calls()).isEqualTo(1);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Path temporaryDatabase() {
        try {
            Path directory = Files.createTempDirectory("navigation-service-served");
            directory.toFile().deleteOnExit();
            Path database = directory.resolve("nav.db");
            database.toFile().deleteOnExit();
            return database;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
