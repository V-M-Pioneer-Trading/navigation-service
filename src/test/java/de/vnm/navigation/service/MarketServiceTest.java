package de.vnm.navigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.client.SpaceTradersClient;
import de.vnm.navigation.model.LocationDataEntity;
import de.vnm.navigation.repository.MarketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketServiceTest {

    @Mock MarketRepository repository;
    @Mock SpaceTradersClient spaceTradersClient;

    MarketService service;
    ObjectMapper objectMapper = new ObjectMapper();

    private static final String AUTH = "Bearer test-token";
    private static final String SYMBOL = "X1-FQ86-B29";
    private static final String SYSTEM = "X1-FQ86";

    @BeforeEach
    void setUp() {
        service = new MarketService(repository, spaceTradersClient, objectMapper);
    }

    @Test
    void getMarket_freshCache_returnsDataWithoutCallingUpstream() {
        LocationDataEntity cached = marketEntity(Instant.now().minusSeconds(10).toString());
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.of(cached));

        JsonNode result = service.getMarket(SYMBOL, AUTH, null, false);

        assertThat(result.path("symbol").asText()).isEqualTo(SYMBOL);
        verifyNoInteractions(spaceTradersClient);
    }

    @Test
    void getMarket_staleCache_refetchesFromUpstream() throws Exception {
        LocationDataEntity cached = marketEntity(Instant.now().minusSeconds(120).toString());
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.of(cached));
        JsonNode upstream = objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","tradeGoods":[]}""");
        when(spaceTradersClient.fetchMarket(SYSTEM, SYMBOL, AUTH, null)).thenReturn(upstream);

        JsonNode result = service.getMarket(SYMBOL, AUTH, null, false);

        assertThat(result.path("symbol").asText()).isEqualTo(SYMBOL);
        verify(spaceTradersClient).fetchMarket(SYSTEM, SYMBOL, AUTH, null);
    }

    @Test
    void getMarket_cacheMiss_fetchesFromUpstreamAndStores() throws Exception {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.empty());
        JsonNode upstream = objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","tradeGoods":[]}""");
        when(spaceTradersClient.fetchMarket(SYSTEM, SYMBOL, AUTH, null)).thenReturn(upstream);

        service.getMarket(SYMBOL, AUTH, null, false);

        ArgumentCaptor<LocationDataEntity> captor = ArgumentCaptor.forClass(LocationDataEntity.class);
        verify(repository).upsert(captor.capture());
        assertThat(captor.getValue().getSymbol()).isEqualTo(SYMBOL);
        assertThat(captor.getValue().getSystemSymbol()).isEqualTo(SYSTEM);
    }

    @Test
    void getMarket_forceRefresh_bypassesCacheAndFetchesUpstream() throws Exception {
        JsonNode upstream = objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","tradeGoods":[]}""");
        when(spaceTradersClient.fetchMarket(SYSTEM, SYMBOL, AUTH, null)).thenReturn(upstream);

        service.getMarket(SYMBOL, AUTH, null, true);

        verify(repository, never()).findBySymbol(any());
        verify(spaceTradersClient).fetchMarket(SYSTEM, SYMBOL, AUTH, null);
    }

    private LocationDataEntity marketEntity(String fetchedAt) {
        return new LocationDataEntity(SYMBOL, SYSTEM, """
                {"symbol":"X1-FQ86-B29","tradeGoods":[]}""", fetchedAt);
    }
}
