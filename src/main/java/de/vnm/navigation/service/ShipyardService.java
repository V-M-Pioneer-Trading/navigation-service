package de.vnm.navigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.client.Priority;
import de.vnm.navigation.client.SpaceTradersClient;
import de.vnm.navigation.repository.ShipyardRepository;
import org.springframework.stereotype.Service;

/**
 * Shipyard data access.
 *
 * <h3>Cache policy</h3>
 * Same as waypoints: shipyard listings change rarely, so a cached row is served
 * indefinitely until the caller explicitly asks for a refresh — hence the {@code null}
 * TTL below, which means "never expires on its own".
 */
@Service
public class ShipyardService extends CachedResourceService {

    private final SpaceTradersClient spaceTradersClient;

    public ShipyardService(ShipyardRepository repository,
                           SpaceTradersClient spaceTradersClient,
                           ObjectMapper objectMapper) {
        super(repository, objectMapper, "shipyard", null);
        this.spaceTradersClient = spaceTradersClient;
    }

    @Override
    protected JsonNode fetchUpstream(String systemSymbol, String waypointSymbol,
                                     String token, Priority priority) {
        return spaceTradersClient.fetchShipyard(systemSymbol, waypointSymbol, token, priority);
    }
}
