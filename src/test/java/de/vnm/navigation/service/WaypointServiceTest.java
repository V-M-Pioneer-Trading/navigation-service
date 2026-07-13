package de.vnm.navigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.client.SpaceTradersClient;
import de.vnm.navigation.exception.UpstreamException;
import de.vnm.navigation.model.WaypointEntity;
import de.vnm.navigation.repository.WaypointRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WaypointServiceTest {

    @Mock WaypointRepository repository;
    @Mock SpaceTradersClient spaceTradersClient;

    WaypointService service;
    ObjectMapper objectMapper = new ObjectMapper();

    private static final String AUTH = "Bearer test-token";
    private static final String SYMBOL = "X1-FQ86-B29";
    private static final String SYSTEM = "X1-FQ86";

    @BeforeEach
    void setUp() {
        service = new WaypointService(repository, spaceTradersClient, objectMapper);
    }

    // ── cache-hit path ───────────────────────────────────────────────────────

    @Test
    void getWaypoint_cacheHit_returnsDataWithoutCallingUpstream() throws Exception {
        WaypointEntity cached = waypointEntity(SYMBOL, SYSTEM, """
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","systemSymbol":"X1-FQ86","x":10,"y":20}""");
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.of(cached));

        JsonNode result = service.getWaypoint(SYMBOL, AUTH, false);

        assertThat(result.path("symbol").asText()).isEqualTo(SYMBOL);
        verifyNoInteractions(spaceTradersClient);
    }

    @Test
    void getWaypointsBySystem_cacheHit_returnsDataWithoutCallingUpstream() throws Exception {
        WaypointEntity cached = waypointEntity(SYMBOL, SYSTEM, """
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","systemSymbol":"X1-FQ86","x":10,"y":20}""");
        when(repository.findBySystemSymbol(SYSTEM)).thenReturn(List.of(cached));

        List<JsonNode> result = service.getWaypointsBySystem(SYSTEM, AUTH, false);

        assertThat(result).hasSize(1);
        verifyNoInteractions(spaceTradersClient);
    }

    // ── cache-miss path ──────────────────────────────────────────────────────

    @Test
    void getWaypoint_cacheMiss_fetchesFromUpstreamAndStores() throws Exception {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.empty());
        JsonNode upstream = objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","systemSymbol":"X1-FQ86","x":10,"y":20}""");
        when(spaceTradersClient.fetchWaypoint(SYSTEM, SYMBOL, AUTH)).thenReturn(upstream);

        JsonNode result = service.getWaypoint(SYMBOL, AUTH, false);

        assertThat(result.path("symbol").asText()).isEqualTo(SYMBOL);
        verify(spaceTradersClient).fetchWaypoint(SYSTEM, SYMBOL, AUTH);

        ArgumentCaptor<WaypointEntity> captor = ArgumentCaptor.forClass(WaypointEntity.class);
        verify(repository).upsert(captor.capture());
        WaypointEntity stored = captor.getValue();
        assertThat(stored.getSymbol()).isEqualTo(SYMBOL);
        assertThat(stored.getSystemSymbol()).isEqualTo(SYSTEM);
    }

    // ── forceRefresh path ────────────────────────────────────────────────────

    @Test
    void getWaypoint_forceRefresh_bypassesCacheAndFetchesUpstream() throws Exception {
        JsonNode upstream = objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","systemSymbol":"X1-FQ86","x":10,"y":20}""");
        when(spaceTradersClient.fetchWaypoint(SYSTEM, SYMBOL, AUTH)).thenReturn(upstream);

        service.getWaypoint(SYMBOL, AUTH, true);

        verify(repository, never()).findBySymbol(any());
        verify(spaceTradersClient).fetchWaypoint(SYSTEM, SYMBOL, AUTH);
    }

    @Test
    void getWaypointsBySystem_forceRefresh_deletesExistingAndRefetchesAll() throws Exception {
        JsonNode w1 = objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","systemSymbol":"X1-FQ86","x":10,"y":20}""");
        when(spaceTradersClient.fetchWaypointsBySystem(SYSTEM, AUTH)).thenReturn(List.of(w1));

        List<JsonNode> result = service.getWaypointsBySystem(SYSTEM, AUTH, true);

        assertThat(result).hasSize(1);
        verify(repository, never()).findBySystemSymbol(any());
        verify(repository).deleteBySystemSymbol(SYSTEM);
        verify(repository).upsert(any(WaypointEntity.class));
    }

    // ── auth propagation ─────────────────────────────────────────────────────

    @Test
    void getWaypoint_authHeaderForwardedToClient() throws Exception {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.empty());
        JsonNode upstream = objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","systemSymbol":"X1-FQ86","x":10,"y":20}""");
        when(spaceTradersClient.fetchWaypoint(anyString(), anyString(), eq(AUTH))).thenReturn(upstream);

        service.getWaypoint(SYMBOL, AUTH, false);

        verify(spaceTradersClient).fetchWaypoint(eq(SYSTEM), eq(SYMBOL), eq(AUTH));
    }

    // ── upstream error propagation ───────────────────────────────────────────

    @Test
    void getWaypoint_upstreamReturns404_throwsUpstreamException() {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.empty());
        when(spaceTradersClient.fetchWaypoint(any(), any(), any()))
                .thenThrow(new UpstreamException(HttpStatus.NOT_FOUND, "Not found"));

        assertThatThrownBy(() -> service.getWaypoint(SYMBOL, AUTH, false))
                .isInstanceOf(UpstreamException.class)
                .satisfies(e -> assertThat(((UpstreamException) e).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── symbol extraction ────────────────────────────────────────────────────

    @Test
    void extractSystemSymbol_correctlyStripsWaypointSuffix() {
        assertThat(WaypointService.extractSystemSymbol("X1-FQ86-B29")).isEqualTo("X1-FQ86");
        assertThat(WaypointService.extractSystemSymbol("SECTOR-SYS-A1B")).isEqualTo("SECTOR-SYS");
    }

    @Test
    void extractSystemSymbol_invalidSymbol_throwsIllegalArgument() {
        assertThatThrownBy(() -> WaypointService.extractSystemSymbol("NOSEPARATOR"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private WaypointEntity waypointEntity(String symbol, String system, String rawJson) {
        return new WaypointEntity(symbol, system, "ASTEROID", 10, 20, rawJson, "2024-01-01T00:00:00Z");
    }
}
