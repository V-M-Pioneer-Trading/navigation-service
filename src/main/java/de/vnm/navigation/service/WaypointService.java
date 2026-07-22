package de.vnm.navigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.client.SpaceTradersClient;
import de.vnm.navigation.exception.UpstreamException;
import de.vnm.navigation.model.WaypointEntity;
import de.vnm.navigation.repository.WaypointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Core business logic for waypoint access.
 *
 * <h3>Cache policy</h3>
 * <ul>
 *   <li>Data is fetched from SpaceTraders on first miss and stored indefinitely.</li>
 *   <li>No TTL — data is refreshed only when the caller explicitly requests it
 *       via {@code forceRefresh=true} or the dedicated refresh endpoints.</li>
 * </ul>
 *
 * <h3>Auth token handling</h3>
 * The caller's {@code Authorization} header is passed through to SpaceTraders
 * on upstream requests and is never persisted.
 */
@Service
public class WaypointService {

    private static final Logger log = LoggerFactory.getLogger(WaypointService.class);

    private final WaypointRepository repository;
    private final SpaceTradersClient spaceTradersClient;
    private final ObjectMapper objectMapper;

    public WaypointService(WaypointRepository repository,
                           SpaceTradersClient spaceTradersClient,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.spaceTradersClient = spaceTradersClient;
        this.objectMapper = objectMapper;
    }

    /**
     * Return a waypoint by symbol, fetching from SpaceTraders on cache miss.
     *
     * @param symbol       waypoint symbol, e.g. {@code X1-FQ86-B29}
     * @param authHeader   full {@code Authorization} header; forwarded to SpaceTraders, never stored
     * @param forceRefresh when {@code true}, bypass cache and re-fetch from upstream
     */
    public JsonNode getWaypoint(String symbol, String authHeader, String priority, boolean forceRefresh) {
        if (!forceRefresh) {
            Optional<WaypointEntity> cached = repository.findBySymbol(symbol);
            if (cached.isPresent()) {
                log.debug("Cache hit for waypoint {}", symbol);
                return parseRawJson(cached.get().getRawJson(), symbol);
            }
        }

        log.debug("Cache miss for waypoint {} — fetching from SpaceTraders", symbol);
        String systemSymbol = extractSystemSymbol(symbol);
        JsonNode data = spaceTradersClient.fetchWaypoint(systemSymbol, symbol, authHeader, priority);
        WaypointEntity entity = toEntity(data);
        repository.upsert(entity);
        return data;
    }

    /**
     * Return all waypoints for a system, fetching from SpaceTraders on cache miss.
     *
     * @param systemSymbol e.g. {@code X1-FQ86}
     * @param authHeader   full {@code Authorization} header; forwarded to SpaceTraders, never stored
     * @param forceRefresh when {@code true}, bypass cache and re-fetch all waypoints from upstream
     */
    public List<JsonNode> getWaypointsBySystem(String systemSymbol, String authHeader, String priority,
                                               boolean forceRefresh) {
        if (!forceRefresh) {
            List<WaypointEntity> cached = repository.findBySystemSymbol(systemSymbol);
            if (!cached.isEmpty()) {
                log.debug("Cache hit for system {} ({} waypoints)", systemSymbol, cached.size());
                return cached.stream()
                             .map(e -> parseRawJson(e.getRawJson(), e.getSymbol()))
                             .toList();
            }
        }

        log.debug("Cache miss for system {} — fetching all waypoints from SpaceTraders",
                systemSymbol);
        List<JsonNode> fetched = spaceTradersClient.fetchWaypointsBySystem(systemSymbol, authHeader, priority);

        // Replace any existing rows for the system atomically within the same thread
        repository.deleteBySystemSymbol(systemSymbol);
        fetched.stream().map(this::toEntity).forEach(repository::upsert);

        return fetched;
    }

    /**
     * Force-refresh a single waypoint from SpaceTraders and update the cache.
     */
    public JsonNode refreshWaypoint(String symbol, String authHeader, String priority) {
        return getWaypoint(symbol, authHeader, priority, true);
    }

    /**
     * Force-refresh all waypoints for a system from SpaceTraders and update the cache.
     */
    public List<JsonNode> refreshWaypointsBySystem(String systemSymbol, String authHeader, String priority) {
        return getWaypointsBySystem(systemSymbol, authHeader, priority, true);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Extract system symbol from a waypoint symbol. {@code X1-FQ86-B29} → {@code X1-FQ86}. */
    static String extractSystemSymbol(String waypointSymbol) {
        int lastDash = waypointSymbol.lastIndexOf('-');
        if (lastDash <= 0) {
            throw new IllegalArgumentException(
                    "Invalid waypoint symbol (expected format SECTOR-SYSTEM-ID): " + waypointSymbol);
        }
        return waypointSymbol.substring(0, lastDash);
    }

    private WaypointEntity toEntity(JsonNode node) {
        String symbol       = node.path("symbol").asText();
        String systemSymbol = node.path("systemSymbol").asText();
        if (systemSymbol.isEmpty()) {
            systemSymbol = extractSystemSymbol(symbol);
        }
        String type = node.path("type").asText("UNKNOWN");
        int x = node.path("x").asInt(0);
        int y = node.path("y").asInt(0);
        String rawJson;
        try {
            rawJson = objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new UpstreamException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize waypoint JSON: " + e.getMessage());
        }
        return new WaypointEntity(symbol, systemSymbol, type, x, y, rawJson,
                Instant.now().toString());
    }

    private JsonNode parseRawJson(String rawJson, String context) {
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception e) {
            throw new UpstreamException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Corrupt cached data for " + context + ": " + e.getMessage());
        }
    }
}
