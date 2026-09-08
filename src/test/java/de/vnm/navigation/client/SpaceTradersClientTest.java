package de.vnm.navigation.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Wire-level tests for the upstream client. Every service-level test mocks this class, so
 * until these existed nothing exercised pagination, the request shape, or the mapping of
 * upstream failures onto HTTP statuses — the layer where most of the bugs actually were.
 */
class SpaceTradersClientTest {

    private static final String BASE = "https://gateway.test/proxy";
    private static final String SYSTEM = "X1-FQ86";
    private static final String WAYPOINT = "X1-FQ86-B29";

    private MockRestServiceServer server;
    private SpaceTradersClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SpaceTradersClient(builder, new ObjectMapper(), BASE);
    }

    /**
     * MockRestServiceServer only fails a request that does not match an expectation; a
     * request that is never made goes unnoticed unless someone verifies. Doing it here
     * covers every test in the class, including tests added later, and means a test that
     * asserts only "this throws" still proves the call went out.
     */
    @AfterEach
    void allExpectedRequestsWereMade() {
        server.verify();
    }

    // ── request shape ────────────────────────────────────────────────────────

    @Test
    void fetchWaypoint_sendsNoCredentialAndNoPriorityHint() {
        server.expect(requestTo(BASE + "/systems/X1-FQ86/waypoints/X1-FQ86-B29"))
              .andExpect(headerDoesNotExist("Authorization"))
              .andExpect(headerDoesNotExist("X-Priority"))
              .andRespond(withSuccess("""
                      {"data":{"symbol":"X1-FQ86-B29","type":"ASTEROID"}}""",
                      MediaType.APPLICATION_JSON));

        JsonNode result = client.fetchWaypoint(SYSTEM, WAYPOINT);

        assertThat(result.path("symbol").asText()).isEqualTo(WAYPOINT);
    }

    @Test
    void fetchShipyard_returnsUnwrappedDataNode() {
        server.expect(requestTo(BASE + "/systems/X1-FQ86/waypoints/X1-FQ86-B29/shipyard"))
              .andRespond(withSuccess("""
                      {"data":{"symbol":"X1-FQ86-B29","shipTypes":[]}}""",
                      MediaType.APPLICATION_JSON));

        JsonNode result = client.fetchShipyard(SYSTEM, WAYPOINT);

        assertThat(result.has("shipTypes")).isTrue();
    }

    // ── failure mapping ──────────────────────────────────────────────────────

    @Test
    void fetchWaypoint_upstream404_keepsTheUpstreamStatus() {
        server.expect(requestTo(BASE + "/systems/X1-FQ86/waypoints/X1-FQ86-B29"))
              .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.fetchWaypoint(SYSTEM, WAYPOINT))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /**
     * An upstream 5xx used to collapse into a bare 502 with the body discarded, which is
     * how {@code 503 SpaceTraders credential not configured} — the gateway's way of saying
     * an operator must act rather than wait — arrived here indistinguishable from a
     * transient outage.
     */
    @Test
    void fetchWaypoint_upstream503_keepsTheStatusAndTheGatewaysSentence() {
        server.expect(requestTo(BASE + "/systems/X1-FQ86/waypoints/X1-FQ86-B29"))
              .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE)
                      .contentType(MediaType.APPLICATION_JSON)
                      .body("""
                              {"error":{"message":"SpaceTraders credential not configured"}}"""));

        assertThatThrownBy(() -> client.fetchWaypoint(SYSTEM, WAYPOINT))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
                .hasMessage("SpaceTraders credential not configured");
    }

    /**
     * Regression: a transport failure (st-gateway down, DNS failure, read timeout) used to
     * escape as a raw {@code ResourceAccessException}, which no handler mapped, so callers
     * saw an undifferentiated 500. It is a 504 rather than a 502 because it is the one
     * verdict this service is entitled to reach on its own: the gateway never answered,
     * where 502 now means it answered with something unusable.
     */
    @Test
    void fetchWaypoint_gatewayUnreachable_becomesGatewayTimeout() {
        server.expect(requestTo(BASE + "/systems/X1-FQ86/waypoints/X1-FQ86-B29"))
              .andRespond(request -> {
                  throw new IOException("Connection refused");
              });

        assertThatThrownBy(() -> client.fetchWaypoint(SYSTEM, WAYPOINT))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT))
                .hasMessageContaining("did not answer");
    }

    /**
     * Regression: a 200 whose body carries no {@code data} envelope used to yield Jackson's
     * MissingNode, which serialises to the literal {@code null}. That was cached as a valid
     * row and returned to callers as {@code 200 null} — permanently, since it then read as
     * a cache hit.
     */
    @Test
    void fetchWaypoint_responseWithoutDataEnvelope_becomesBadGateway() {
        server.expect(requestTo(BASE + "/systems/X1-FQ86/waypoints/X1-FQ86-B29"))
              .andRespond(withSuccess("""
                      {"error":{"message":"something else entirely"}}""",
                      MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.fetchWaypoint(SYSTEM, WAYPOINT))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessageContaining("no data payload");
    }

    @Test
    void fetchWaypoint_unparseableBody_becomesBadGateway() {
        server.expect(requestTo(BASE + "/systems/X1-FQ86/waypoints/X1-FQ86-B29"))
              .andRespond(withSuccess("<html>gateway error</html>", MediaType.TEXT_HTML));

        assertThatThrownBy(() -> client.fetchWaypoint(SYSTEM, WAYPOINT))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    // ── pagination ───────────────────────────────────────────────────────────

    @Test
    void fetchWaypointsBySystem_followsPagesUntilMetaTotalIsCovered() {
        expectPage(1, page(20, 23));
        expectPage(2, page(3, 23));

        List<JsonNode> all = client.fetchWaypointsBySystem(SYSTEM);

        assertThat(all).hasSize(23);
    }

    @Test
    void fetchWaypointsBySystem_singleShortPage_stopsImmediately() {
        expectPage(1, page(4, 4));

        assertThat(client.fetchWaypointsBySystem(SYSTEM)).hasSize(4);
    }

    /**
     * Regression: {@code meta.total} was the only stop condition and defaulted to 0 when the
     * key was absent, so a response without {@code meta} ended the walk after one page. A
     * 23-waypoint system was then cached — and served — as 20 waypoints.
     */
    @Test
    void fetchWaypointsBySystem_responseWithoutMeta_keepsPagingUntilAShortPage() {
        expectPage(1, pageWithoutMeta(20));
        expectPage(2, pageWithoutMeta(3));

        List<JsonNode> all = client.fetchWaypointsBySystem(SYSTEM);

        assertThat(all).hasSize(23);
    }

    @Test
    void fetchWaypointsBySystem_dataNotAnArray_becomesBadGateway() {
        expectPage(1, """
                {"data":{"symbol":"X1-FQ86-B29"},"meta":{"total":1}}""");

        assertThatThrownBy(() -> client.fetchWaypointsBySystem(SYSTEM))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void expectPage(int page, String body) {
        server.expect(requestTo(BASE + "/systems/X1-FQ86/waypoints?page=" + page + "&limit=20"))
              .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private static String page(int count, int total) {
        return "{\"data\":" + waypoints(count) + ",\"meta\":{\"total\":" + total + "}}";
    }

    private static String pageWithoutMeta(int count) {
        return "{\"data\":" + waypoints(count) + "}";
    }

    private static String waypoints(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> "{\"symbol\":\"X1-FQ86-W" + i + "\",\"systemSymbol\":\"X1-FQ86\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }
}
