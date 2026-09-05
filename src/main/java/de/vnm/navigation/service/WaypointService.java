package de.vnm.navigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.auth.Session;
import de.vnm.navigation.client.SpaceTradersClient;
import de.vnm.navigation.exception.ApiException;
import de.vnm.navigation.model.WaypointEntity;
import de.vnm.navigation.repository.SystemCacheRepository;
import de.vnm.navigation.repository.WaypointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Waypoint access.
 *
 * <h3>Cache policy</h3>
 * A waypoint is fetched on first miss and stored indefinitely — waypoints do not move.
 * Refresh happens only when the caller asks for it.
 *
 * <h3>Whole-system listings</h3>
 * A system listing is only served from cache once a <em>complete</em> walk of that system
 * has been recorded in {@code system_fetches}. Rows alone are not enough: a single
 * {@code GET /waypoints/{symbol}} stores one row for the system, and treating that as a
 * cache hit made the listing endpoint answer "this system has one waypoint" forever.
 *
 * <h3>Who may fetch live</h3>
 * A cache hit needs no identity at all, which is what makes reads public; a live fetch
 * requires a verified Clerk session (auth-design.md decisions 2 and 3). The upstream
 * credential is st-gateway's concern, not this service's (decision 5).
 */
@Service
public class WaypointService {

    private static final Logger log = LoggerFactory.getLogger(WaypointService.class);

    private final WaypointRepository repository;
    private final SystemCacheRepository systemCache;
    private final SpaceTradersClient spaceTradersClient;
    private final CachedJson json;

    public WaypointService(WaypointRepository repository,
                           SystemCacheRepository systemCache,
                           SpaceTradersClient spaceTradersClient,
                           ObjectMapper objectMapper) {
        this.repository = repository;
        this.systemCache = systemCache;
        this.spaceTradersClient = spaceTradersClient;
        this.json = new CachedJson(objectMapper);
    }

    /**
     * Return a waypoint by symbol, fetching from SpaceTraders on cache miss.
     *
     * @param symbol       waypoint symbol, e.g. {@code X1-FQ86-B29}
     * @param session      the verified caller, or {@code null} for an anonymous, cache-only read
     * @param forceRefresh when {@code true}, bypass the cache and re-fetch
     */
    public JsonNode getWaypoint(String symbol, Session session, boolean forceRefresh) {
        String systemSymbol = Symbols.systemOf(symbol);
        String context = "waypoint " + symbol;

        if (!forceRefresh) {
            Optional<WaypointEntity> cached = repository.findBySymbol(symbol);
            if (cached.isPresent()) {
                log.debug("Cache hit for {}", context);
                return json.read(cached.get().getRawJson(), context);
            }
        }

        LiveFetch.requireSession(session, context);
        log.debug("Cache miss for {} — fetching from SpaceTraders", context);
        JsonNode data = spaceTradersClient.fetchWaypoint(systemSymbol, symbol);
        repository.upsert(toEntity(data));
        return data;
    }

    /**
     * Return every waypoint in a system, fetching the whole system on cache miss.
     *
     * <p>Transactional: the refresh replaces the system's rows, and a failure part-way
     * through must leave the previous listing intact rather than a half-written one.
     *
     * @param forceRefresh when {@code true}, bypass the cache and re-walk the system
     */
    @Transactional
    public List<JsonNode> getWaypointsBySystem(String systemSymbol, Session session, boolean forceRefresh) {
        Symbols.requireSystem(systemSymbol);
        String context = "system " + systemSymbol;

        if (!forceRefresh && systemCache.isComplete(systemSymbol)) {
            List<WaypointEntity> cached = repository.findBySystemSymbol(systemSymbol);
            log.debug("Cache hit for {} ({} waypoints)", context, cached.size());
            return cached.stream()
                         .map(e -> json.read(e.getRawJson(), "waypoint " + e.getSymbol()))
                         .toList();
        }

        LiveFetch.requireSession(session, context);
        log.debug("Cache miss for {} — fetching all waypoints from SpaceTraders", context);
        List<JsonNode> fetched = spaceTradersClient.fetchWaypointsBySystem(systemSymbol);

        // Map before writing: an unusable waypoint in the response aborts the refresh
        // while the previously cached listing is still on disk.
        List<WaypointEntity> rows = fetched.stream().map(this::toEntity).toList();
        repository.deleteBySystemSymbol(systemSymbol);
        rows.forEach(repository::upsert);
        systemCache.markComplete(systemSymbol);

        return fetched;
    }

    /** Force-refresh a single waypoint from SpaceTraders and update the cache. */
    public JsonNode refreshWaypoint(String symbol, Session session) {
        return getWaypoint(symbol, session, true);
    }

    /**
     * Force-refresh every waypoint in a system and replace the cached listing.
     *
     * <p>Transactional for the same reason as {@link #getWaypointsBySystem}; it also has to
     * carry the annotation itself, because a self-call would not go through the proxy.
     */
    @Transactional
    public List<JsonNode> refreshWaypointsBySystem(String systemSymbol, Session session) {
        return getWaypointsBySystem(systemSymbol, session, true);
    }

    private WaypointEntity toEntity(JsonNode node) {
        String symbol = node.path("symbol").asText();
        if (symbol.isBlank()) {
            // Storing this would key a row on "" and poison every later lookup.
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "SpaceTraders returned a waypoint without a symbol");
        }
        String systemSymbol = node.path("systemSymbol").asText();
        if (systemSymbol.isBlank()) {
            systemSymbol = Symbols.systemOf(symbol);
        }
        return new WaypointEntity(
                symbol,
                systemSymbol,
                node.path("type").asText("UNKNOWN"),
                node.path("x").asInt(0),
                node.path("y").asInt(0),
                json.write(node, "waypoint " + symbol),
                Instant.now().toString());
    }
}
