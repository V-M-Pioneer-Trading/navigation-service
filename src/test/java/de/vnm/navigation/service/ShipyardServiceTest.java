package de.vnm.navigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.client.SpaceTradersClient;
import de.vnm.navigation.exception.UpstreamException;
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

    private static final String AUTH = "Bearer test-token";
    private static final String SYMBOL = "X1-FQ86-B29";
    private static final String SYSTEM = "X1-FQ86";

    @BeforeEach
    void setUp() {
        service = new ShipyardService(repository, spaceTradersClient, objectMapper);
    }

    @Test
    void getShipyard_cacheHit_returnsDataWithoutCallingUpstream() {
        LocationDataEntity cached = shipyardEntity();
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.of(cached));

        JsonNode result = service.getShipyard(SYMBOL, AUTH, null, false);

        assertThat(result.path("symbol").asText()).isEqualTo(SYMBOL);
        verifyNoInteractions(spaceTradersClient);
    }

    @Test
    void getShipyard_cacheMiss_fetchesFromUpstreamAndStores() throws Exception {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.empty());
        JsonNode upstream = objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","shipTypes":[]}""");
        when(spaceTradersClient.fetchShipyard(SYSTEM, SYMBOL, AUTH, null)).thenReturn(upstream);

        service.getShipyard(SYMBOL, AUTH, null, false);

        ArgumentCaptor<LocationDataEntity> captor = ArgumentCaptor.forClass(LocationDataEntity.class);
        verify(repository).upsert(captor.capture());
        assertThat(captor.getValue().getSymbol()).isEqualTo(SYMBOL);
        assertThat(captor.getValue().getSystemSymbol()).isEqualTo(SYSTEM);
    }

    @Test
    void getShipyard_forceRefresh_bypassesCacheAndFetchesUpstream() throws Exception {
        JsonNode upstream = objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","shipTypes":[]}""");
        when(spaceTradersClient.fetchShipyard(SYSTEM, SYMBOL, AUTH, null)).thenReturn(upstream);

        service.getShipyard(SYMBOL, AUTH, null, true);

        verify(repository, never()).findBySymbol(any());
        verify(spaceTradersClient).fetchShipyard(SYSTEM, SYMBOL, AUTH, null);
    }

    // ── anonymous callers (auth-design.md decision 18) ──────────────────────

    @Test
    void getShipyard_cacheHit_noAuthHeaderAtAll_stillSucceeds() {
        LocationDataEntity cached = shipyardEntity();
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.of(cached));

        JsonNode result = service.getShipyard(SYMBOL, null, null, false);

        assertThat(result.path("symbol").asText()).isEqualTo(SYMBOL);
        verifyNoInteractions(spaceTradersClient);
    }

    @Test
    void getShipyard_cacheMiss_noAuthHeader_throwsUnauthorizedWithoutCallingUpstream() {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getShipyard(SYMBOL, null, null, false))
                .isInstanceOf(UpstreamException.class)
                .satisfies(e -> assertThat(((UpstreamException) e).getStatus())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(spaceTradersClient);
    }

    private LocationDataEntity shipyardEntity() {
        return new LocationDataEntity(SYMBOL, SYSTEM, """
                {"symbol":"X1-FQ86-B29","shipTypes":[]}""", "2024-01-01T00:00:00Z");
    }
}
