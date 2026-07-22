package de.vnm.navigation.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.exception.UpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP client for the SpaceTraders v2 API, routed through st-gateway's shared
 * rate budget (meta#1/meta#7) rather than hitting SpaceTraders directly.
 * <p>
 * The caller's {@code Authorization: Bearer <token>} header is forwarded on every
 * request and is never stored by this service.
 * <p>
 * Every fetch* method takes the caller's own {@code X-Priority} declaration and
 * forwards it through to st-gateway's priority queue (meta#37) —
 * command-interface (browser) sends {@code interactive}, automation-service
 * (autopilot) sends nothing. {@link #normalizePriority} degrades anything but
 * exactly {@code interactive} to {@code background} so a missing or malformed
 * header never accidentally jumps the queue.
 */
@Component
public class SpaceTradersClient {

    private static final Logger log = LoggerFactory.getLogger(SpaceTradersClient.class);

    private static final int PAGE_LIMIT = 20;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public SpaceTradersClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${spacetraders.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    private static String normalizePriority(String priority) {
        return "interactive".equals(priority) ? "interactive" : "background";
    }

    /**
     * Fetch a single waypoint from SpaceTraders.
     *
     * @param systemSymbol   e.g. {@code X1-FQ86}
     * @param waypointSymbol e.g. {@code X1-FQ86-B29}
     * @param authHeader     full {@code Authorization} header value (e.g. {@code Bearer <token>})
     * @param priority       caller's {@code X-Priority} declaration ({@code interactive} or anything else)
     * @return the waypoint {@link JsonNode} as returned by SpaceTraders
     */
    public JsonNode fetchWaypoint(String systemSymbol, String waypointSymbol, String authHeader, String priority) {
        log.debug("Fetching waypoint {} from SpaceTraders", waypointSymbol);
        String body = restClient.get()
                .uri("/systems/{system}/waypoints/{waypoint}", systemSymbol, waypointSymbol)
                .header("Authorization", authHeader)
                .header("X-Priority", normalizePriority(priority))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    HttpStatus status = HttpStatus.resolve(res.getStatusCode().value());
                    String msg = "SpaceTraders returned " + res.getStatusCode() +
                                 " for waypoint " + waypointSymbol;
                    throw new UpstreamException(
                            status != null ? status : HttpStatus.BAD_GATEWAY, msg);
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    throw new UpstreamException(HttpStatus.BAD_GATEWAY,
                            "SpaceTraders upstream error: " + res.getStatusCode());
                })
                .body(String.class);

        return parseData(body, waypointSymbol);
    }

    /**
     * Fetch market data for a waypoint from SpaceTraders.
     *
     * @param systemSymbol   e.g. {@code X1-FQ86}
     * @param waypointSymbol e.g. {@code X1-FQ86-B29}
     * @param authHeader     full {@code Authorization} header value (e.g. {@code Bearer <token>})
     */
    public JsonNode fetchMarket(String systemSymbol, String waypointSymbol, String authHeader, String priority) {
        log.debug("Fetching market for {} from SpaceTraders", waypointSymbol);
        String body = restClient.get()
                .uri("/systems/{system}/waypoints/{waypoint}/market", systemSymbol, waypointSymbol)
                .header("Authorization", authHeader)
                .header("X-Priority", normalizePriority(priority))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    HttpStatus status = HttpStatus.resolve(res.getStatusCode().value());
                    String msg = "SpaceTraders returned " + res.getStatusCode() +
                                 " for market at " + waypointSymbol;
                    throw new UpstreamException(
                            status != null ? status : HttpStatus.BAD_GATEWAY, msg);
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    throw new UpstreamException(HttpStatus.BAD_GATEWAY,
                            "SpaceTraders upstream error: " + res.getStatusCode());
                })
                .body(String.class);

        return parseData(body, waypointSymbol);
    }

    /**
     * Fetch shipyard data for a waypoint from SpaceTraders.
     *
     * @param systemSymbol   e.g. {@code X1-FQ86}
     * @param waypointSymbol e.g. {@code X1-FQ86-B29}
     * @param authHeader     full {@code Authorization} header value (e.g. {@code Bearer <token>})
     */
    public JsonNode fetchShipyard(String systemSymbol, String waypointSymbol, String authHeader, String priority) {
        log.debug("Fetching shipyard for {} from SpaceTraders", waypointSymbol);
        String body = restClient.get()
                .uri("/systems/{system}/waypoints/{waypoint}/shipyard", systemSymbol, waypointSymbol)
                .header("Authorization", authHeader)
                .header("X-Priority", normalizePriority(priority))
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                    HttpStatus status = HttpStatus.resolve(res.getStatusCode().value());
                    String msg = "SpaceTraders returned " + res.getStatusCode() +
                                 " for shipyard at " + waypointSymbol;
                    throw new UpstreamException(
                            status != null ? status : HttpStatus.BAD_GATEWAY, msg);
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                    throw new UpstreamException(HttpStatus.BAD_GATEWAY,
                            "SpaceTraders upstream error: " + res.getStatusCode());
                })
                .body(String.class);

        return parseData(body, waypointSymbol);
    }

    /**
     * Fetch all waypoints for a system, following pagination automatically.
     *
     * @param systemSymbol e.g. {@code X1-FQ86}
     * @param authHeader   full {@code Authorization} header value
     * @return list of waypoint {@link JsonNode}s
     */
    public List<JsonNode> fetchWaypointsBySystem(String systemSymbol, String authHeader, String priority) {
        log.debug("Fetching all waypoints for system {} from SpaceTraders", systemSymbol);
        List<JsonNode> all = new ArrayList<>();
        int page = 1;
        int total;

        do {
            final int currentPage = page;
            String body = restClient.get()
                    .uri(u -> u.path("/systems/{system}/waypoints")
                               .queryParam("page", currentPage)
                               .queryParam("limit", PAGE_LIMIT)
                               .build(systemSymbol))
                    .header("Authorization", authHeader)
                    .header("X-Priority", normalizePriority(priority))
                    .retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        HttpStatus status = HttpStatus.resolve(res.getStatusCode().value());
                        throw new UpstreamException(
                                status != null ? status : HttpStatus.BAD_GATEWAY,
                                "SpaceTraders returned " + res.getStatusCode() +
                                " for system " + systemSymbol);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new UpstreamException(HttpStatus.BAD_GATEWAY,
                                "SpaceTraders upstream error: " + res.getStatusCode());
                    })
                    .body(String.class);

            try {
                JsonNode root = objectMapper.readTree(body);
                JsonNode data = root.path("data");
                JsonNode meta = root.path("meta");

                if (!data.isArray()) {
                    throw new UpstreamException(HttpStatus.BAD_GATEWAY,
                            "Unexpected response from SpaceTraders for system " + systemSymbol);
                }
                data.forEach(all::add);

                total = meta.path("total").asInt(0);
                page++;
            } catch (UpstreamException e) {
                throw e;
            } catch (Exception e) {
                throw new UpstreamException(HttpStatus.BAD_GATEWAY,
                        "Failed to parse SpaceTraders response: " + e.getMessage());
            }
        } while ((long) (page - 1) * PAGE_LIMIT < total);

        log.debug("Fetched {} waypoints for system {}", all.size(), systemSymbol);
        return all;
    }

    private JsonNode parseData(String body, String context) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.path("data");
        } catch (Exception e) {
            throw new UpstreamException(HttpStatus.BAD_GATEWAY,
                    "Failed to parse SpaceTraders response for " + context + ": " + e.getMessage());
        }
    }
}
