package de.vnm.navigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.client.Priority;
import de.vnm.navigation.client.SpaceTradersClient;
import de.vnm.navigation.repository.MarketRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Market data access.
 *
 * <h3>Cache policy</h3>
 * Unlike waypoints and shipyards, market prices move with trading activity, so a cached
 * row is served only while it is younger than {@code navigation.market.cache-ttl}
 * (60 seconds by default). An older row is treated as a miss.
 */
@Service
public class MarketService extends CachedResourceService {

    private final SpaceTradersClient spaceTradersClient;

    public MarketService(MarketRepository repository,
                         SpaceTradersClient spaceTradersClient,
                         ObjectMapper objectMapper,
                         @Value("${navigation.market.cache-ttl:60s}") Duration ttl) {
        super(repository, objectMapper, "market", ttl);
        this.spaceTradersClient = spaceTradersClient;
    }

    @Override
    protected JsonNode fetchUpstream(String systemSymbol, String waypointSymbol,
                                     String token, Priority priority) {
        return spaceTradersClient.fetchMarket(systemSymbol, waypointSymbol, token, priority);
    }
}
