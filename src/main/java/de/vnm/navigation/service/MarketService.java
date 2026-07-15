package de.vnm.navigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.client.SpaceTradersClient;
import de.vnm.navigation.exception.UpstreamException;
import de.vnm.navigation.model.LocationDataEntity;
import de.vnm.navigation.repository.MarketRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Core business logic for market data access.
 *
 * <h3>Cache policy</h3>
 * Unlike waypoints (cached forever), market prices move with trading activity. A cached row
 * is only served if it was fetched within the last {@link #TTL}; otherwise it's treated as a
 * miss and re-fetched, same as an explicit {@code forceRefresh=true}.
 */
@Service
public class MarketService {

    private static final Logger log = LoggerFactory.getLogger(MarketService.class);
    private static final Duration TTL = Duration.ofSeconds(60);

    private final MarketRepository repository;
    private final SpaceTradersClient spaceTradersClient;
    private final ObjectMapper objectMapper;

    public MarketService(MarketRepository repository,
                          SpaceTradersClient spaceTradersClient,
                          ObjectMapper objectMapper) {
        this.repository = repository;
        this.spaceTradersClient = spaceTradersClient;
        this.objectMapper = objectMapper;
    }

    public JsonNode getMarket(String waypointSymbol, String authHeader, boolean forceRefresh) {
        if (!forceRefresh) {
            Optional<LocationDataEntity> cached = repository.findBySymbol(waypointSymbol);
            if (cached.isPresent() && !isStale(cached.get())) {
                log.debug("Cache hit for market {}", waypointSymbol);
                return parseRawJson(cached.get().getRawJson(), waypointSymbol);
            }
        }

        log.debug("Cache miss for market {} — fetching from SpaceTraders", waypointSymbol);
        String systemSymbol = WaypointService.extractSystemSymbol(waypointSymbol);
        JsonNode data = spaceTradersClient.fetchMarket(systemSymbol, waypointSymbol, authHeader);
        repository.upsert(toEntity(waypointSymbol, systemSymbol, data));
        return data;
    }

    public JsonNode refreshMarket(String waypointSymbol, String authHeader) {
        return getMarket(waypointSymbol, authHeader, true);
    }

    private boolean isStale(LocationDataEntity entity) {
        Instant fetchedAt = Instant.parse(entity.getFetchedAt());
        return fetchedAt.isBefore(Instant.now().minus(TTL));
    }

    private LocationDataEntity toEntity(String symbol, String systemSymbol, JsonNode data) {
        String rawJson;
        try {
            rawJson = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new UpstreamException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize market JSON: " + e.getMessage());
        }
        return new LocationDataEntity(symbol, systemSymbol, rawJson, Instant.now().toString());
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
