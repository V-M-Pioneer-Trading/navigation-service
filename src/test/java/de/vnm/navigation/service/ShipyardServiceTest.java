package de.vnm.navigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.auth.Session;
import de.vnm.navigation.client.SpaceTradersClient;
import de.vnm.navigation.exception.ApiException;
import de.vnm.navigation.model.LocationDataEntity;
import de.vnm.navigation.repository.ShipyardRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShipyardServiceTest {

    @Mock ShipyardRepository repository;
    @Mock SpaceTradersClient spaceTradersClient;

    ShipyardService service;
    ObjectMapper objectMapper = new ObjectMapper();

    private static final Session OPERATOR = new Session("user_test", java.util.Set.of("universe:refresh"));
    private static final String SYMBOL = "X1-FQ86-B29";
    private static final String SYSTEM = "X1-FQ86";

    @BeforeEach
    void setUp() {
        service = new ShipyardService(repository, spaceTradersClient, objectMapper);
    }

    @Test
    void get_cacheHit_returnsDataWithoutCallingUpstream() {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.of(shipyardEntity()));

        JsonNode result = service.get(SYMBOL, OPERATOR, false);

        assertThat(result.path("symbol").asText()).isEqualTo(SYMBOL);
        verifyNoInteractions(spaceTradersClient);
    }

    /** Shipyards have no TTL: a row from 2024 is still a hit. */
    @Test
    void get_veryOldCacheRow_isStillServed() {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.of(shipyardEntity()));

        service.get(SYMBOL, OPERATOR, false);

        verifyNoInteractions(spaceTradersClient);
    }

    @Test
    void get_cacheMiss_fetchesFromUpstreamAndStores() throws Exception {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.empty());
        when(spaceTradersClient.fetchShipyard(SYSTEM, SYMBOL))
                .thenReturn(upstreamShipyard());

        service.get(SYMBOL, OPERATOR, false);

        ArgumentCaptor<LocationDataEntity> captor = ArgumentCaptor.forClass(LocationDataEntity.class);
        verify(repository).upsert(captor.capture());
        assertThat(captor.getValue().getSymbol()).isEqualTo(SYMBOL);
        assertThat(captor.getValue().getSystemSymbol()).isEqualTo(SYSTEM);
    }

    @Test
    void refresh_bypassesCacheAndFetchesUpstream() throws Exception {
        when(spaceTradersClient.fetchShipyard(SYSTEM, SYMBOL))
                .thenReturn(upstreamShipyard());

        service.refresh(SYMBOL, OPERATOR);

        verify(repository, never()).findBySymbol(any());
        verify(spaceTradersClient).fetchShipyard(SYSTEM, SYMBOL);
    }

    // ── anonymous callers (auth-design.md decision 18) ──────────────────────

    @Test
    void get_cacheHit_noTokenAtAll_stillSucceeds() {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.of(shipyardEntity()));

        JsonNode result = service.get(SYMBOL, null, false);

        assertThat(result.path("symbol").asText()).isEqualTo(SYMBOL);
        verifyNoInteractions(spaceTradersClient);
    }

    @Test
    void get_cacheMiss_noToken_throwsUnauthorizedWithoutCallingUpstream() {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(SYMBOL, null, false))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(spaceTradersClient);
    }

    private JsonNode upstreamShipyard() throws Exception {
        return objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","shipTypes":[]}""");
    }

    private LocationDataEntity shipyardEntity() {
        return new LocationDataEntity(SYMBOL, SYSTEM, """
                {"symbol":"X1-FQ86-B29","shipTypes":[]}""", "2024-01-01T00:00:00Z");
    }
}
