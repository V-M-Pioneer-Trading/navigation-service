package de.vnm.navigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.client.SpaceTradersClient;
import de.vnm.navigation.exception.UpstreamException;
import de.vnm.navigation.model.LocationDataEntity;
import de.vnm.navigation.repository.ShipyardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

/**
 * Core business logic for shipyard data access.
 *
 * <h3>Cache policy</h3>
 * Same as waypoints: shipyard listings (ships for sale) change rarely, so a cached row is
 * served indefinitely until the caller explicitly requests {@code forceRefresh=true}.
 */
@Service
public class ShipyardService {

    private static final Logger log = LoggerFactory.getLogger(ShipyardService.class);

    private final ShipyardRepository repository;
    private final SpaceTradersClient spaceTradersClient;
    private final ObjectMapper objectMapper;

    public ShipyardService(ShipyardRepository repository,
                            SpaceTradersClient spaceTradersClient,
                            ObjectMapper objectMapper) {
        this.repository = repository;
        this.spaceTradersClient = spaceTradersClient;
        this.objectMapper = objectMapper;
    }

    public JsonNode getShipyard(String waypointSymbol, String authHeader, boolean forceRefresh) {
        if (!forceRefresh) {
            Optional<LocationDataEntity> cached = repository.findBySymbol(waypointSymbol);
            if (cached.isPresent()) {
                log.debug("Cache hit for shipyard {}", waypointSymbol);
                return parseRawJson(cached.get().getRawJson(), waypointSymbol);
            }
        }

        log.debug("Cache miss for shipyard {} — fetching from SpaceTraders", waypointSymbol);
        String systemSymbol = WaypointService.extractSystemSymbol(waypointSymbol);
        JsonNode data = spaceTradersClient.fetchShipyard(systemSymbol, waypointSymbol, authHeader);
        repository.upsert(toEntity(waypointSymbol, systemSymbol, data));
        return data;
    }

    public JsonNode refreshShipyard(String waypointSymbol, String authHeader) {
        return getShipyard(waypointSymbol, authHeader, true);
    }

    private LocationDataEntity toEntity(String symbol, String systemSymbol, JsonNode data) {
        String rawJson;
        try {
            rawJson = objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            throw new UpstreamException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize shipyard JSON: " + e.getMessage());
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
