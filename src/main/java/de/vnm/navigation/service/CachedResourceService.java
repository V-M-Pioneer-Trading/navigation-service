package de.vnm.navigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.client.Priority;
import de.vnm.navigation.model.LocationDataEntity;
import de.vnm.navigation.repository.LocationDataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The cache-then-fetch sequence shared by every SpaceTraders resource keyed by a single
 * waypoint symbol (market, shipyard). Subclasses supply only what actually differs: the
 * table-backed repository, the upstream call, a label for logs and errors, and how long a
 * cached row stays good.
 *
 * <p>The sequence is: validate the symbol, serve a fresh cached row if one exists and the
 * caller did not ask for a refresh, otherwise require a credential, fetch, store, return.
 */
public abstract class CachedResourceService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final LocationDataRepository repository;
    private final CachedJson json;
    private final String label;

    /** How long a cached row stays servable; {@code null} means it never expires on its own. */
    private final Duration ttl;

    protected CachedResourceService(LocationDataRepository repository, ObjectMapper objectMapper,
                                    String label, Duration ttl) {
        if (ttl != null && ttl.isNegative()) {
            throw new IllegalArgumentException(label + " cache TTL must not be negative: " + ttl);
        }
        this.repository = repository;
        this.json = new CachedJson(objectMapper);
        this.label = label;
        this.ttl = ttl;
    }

    /** Fetch this resource for one waypoint from SpaceTraders. */
    protected abstract JsonNode fetchUpstream(String systemSymbol, String waypointSymbol,
                                              String token, Priority priority);

    /**
     * @param waypointSymbol waypoint the resource belongs to, e.g. {@code X1-FQ86-B29}
     * @param token          bare SpaceTraders token, or {@code null} for a cache-only read
     * @param forceRefresh   when {@code true}, skip the cache and re-fetch
     */
    public JsonNode get(String waypointSymbol, String token, Priority priority, boolean forceRefresh) {
        String systemSymbol = Symbols.systemOf(waypointSymbol);
        String context = label + " " + waypointSymbol;

        if (!forceRefresh) {
            Optional<LocationDataEntity> cached = repository.findBySymbol(waypointSymbol);
            if (cached.isPresent() && isFresh(cached.get())) {
                log.debug("Cache hit for {}", context);
                return json.read(cached.get().getRawJson(), context);
            }
        }

        Credentials.requireForLiveFetch(token, context);
        log.debug("Cache miss for {} — fetching from SpaceTraders", context);
        JsonNode data = fetchUpstream(systemSymbol, waypointSymbol, token, priority);
        repository.upsert(new LocationDataEntity(
                waypointSymbol, systemSymbol, json.write(data, context), Instant.now().toString()));
        return data;
    }

    /** Force-fetch from SpaceTraders and update the cache. */
    public JsonNode refresh(String waypointSymbol, String token, Priority priority) {
        return get(waypointSymbol, token, priority, true);
    }

    private boolean isFresh(LocationDataEntity entity) {
        if (ttl == null) {
            return true;
        }
        Instant fetchedAt = CachedJson.fetchedAtOrNull(entity.getFetchedAt());
        return fetchedAt != null && !fetchedAt.isBefore(Instant.now().minus(ttl));
    }
}
