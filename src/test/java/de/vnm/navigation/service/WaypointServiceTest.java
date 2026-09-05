package de.vnm.navigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.auth.Session;
import de.vnm.navigation.client.SpaceTradersClient;
import de.vnm.navigation.exception.ApiException;
import de.vnm.navigation.model.WaypointEntity;
import de.vnm.navigation.repository.SystemCacheRepository;
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
    @Mock SystemCacheRepository systemCache;
    @Mock SpaceTradersClient spaceTradersClient;

    WaypointService service;
    ObjectMapper objectMapper = new ObjectMapper();

    private static final Session OPERATOR = new Session("user_test", java.util.Set.of("universe:refresh"));
    private static final String SYMBOL = "X1-FQ86-B29";
    private static final String SYSTEM = "X1-FQ86";

    @BeforeEach
    void setUp() {
        service = new WaypointService(repository, systemCache, spaceTradersClient, objectMapper);
    }

    // ── cache-hit path ───────────────────────────────────────────────────────

    @Test
    void getWaypoint_cacheHit_returnsDataWithoutCallingUpstream() {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.of(waypointEntity()));

        JsonNode result = service.getWaypoint(SYMBOL, OPERATOR, false);

        assertThat(result.path("symbol").asText()).isEqualTo(SYMBOL);
        verifyNoInteractions(spaceTradersClient);
    }

    @Test
    void getWaypointsBySystem_completeSystemInCache_returnsDataWithoutCallingUpstream() {
        when(systemCache.isComplete(SYSTEM)).thenReturn(true);
        when(repository.findBySystemSymbol(SYSTEM)).thenReturn(List.of(waypointEntity()));

        List<JsonNode> result = service.getWaypointsBySystem(SYSTEM, OPERATOR, false);

        assertThat(result).hasSize(1);
        verifyNoInteractions(spaceTradersClient);
    }

    /**
     * Regression: the listing served whatever rows the system happened to have. A single
     * {@code GET /waypoints/X1-FQ86-B29} stores one row for X1-FQ86, and every later listing
     * of that system answered "one waypoint" — a permanently truncated, wrong answer, since
     * that same non-empty result also suppressed the upstream walk that would have fixed it.
     */
    @Test
    void getWaypointsBySystem_rowsPresentButSystemNeverFullyWalked_fetchesUpstream() throws Exception {
        when(systemCache.isComplete(SYSTEM)).thenReturn(false);
        when(spaceTradersClient.fetchWaypointsBySystem(SYSTEM))
                .thenReturn(List.of(upstreamWaypoint()));

        List<JsonNode> result = service.getWaypointsBySystem(SYSTEM, OPERATOR, false);

        assertThat(result).hasSize(1);
        verify(repository, never()).findBySystemSymbol(any());
        verify(spaceTradersClient).fetchWaypointsBySystem(SYSTEM);
        verify(systemCache).markComplete(SYSTEM);
    }

    // ── cache-miss path ──────────────────────────────────────────────────────

    @Test
    void getWaypoint_cacheMiss_fetchesFromUpstreamAndStores() throws Exception {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.empty());
        when(spaceTradersClient.fetchWaypoint(SYSTEM, SYMBOL))
                .thenReturn(upstreamWaypoint());

        JsonNode result = service.getWaypoint(SYMBOL, OPERATOR, false);

        assertThat(result.path("symbol").asText()).isEqualTo(SYMBOL);

        ArgumentCaptor<WaypointEntity> captor = ArgumentCaptor.forClass(WaypointEntity.class);
        verify(repository).upsert(captor.capture());
        assertThat(captor.getValue().getSymbol()).isEqualTo(SYMBOL);
        assertThat(captor.getValue().getSystemSymbol()).isEqualTo(SYSTEM);
    }

    /**
     * Regression: a waypoint node with no {@code symbol} was stored anyway, under the empty
     * string, and only failed afterwards while deriving its system symbol — surfacing as a
     * 400 "invalid waypoint symbol" for a request whose symbol was perfectly valid.
     */
    @Test
    void getWaypoint_upstreamWaypointWithoutSymbol_isRejectedAsBadGateway() throws Exception {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.empty());
        when(spaceTradersClient.fetchWaypoint(SYSTEM, SYMBOL))
                .thenReturn(objectMapper.readTree("""
                        {"type":"ASTEROID","x":10,"y":20}"""));

        assertThatThrownBy(() -> service.getWaypoint(SYMBOL, OPERATOR, false))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.BAD_GATEWAY));
        verify(repository, never()).upsert(any());
    }

    // ── symbol validation ────────────────────────────────────────────────────

    /**
     * Regression: only the waypoint paths validated their symbol. A system symbol was passed
     * through to the upstream URL unchecked, so junk reached st-gateway and came back as a
     * 502 or an empty listing instead of a 400.
     */
    @Test
    void getWaypointsBySystem_malformedSystemSymbol_isRejectedBeforeUpstream() {
        assertThatThrownBy(() ->
                service.getWaypointsBySystem("X1 FQ86", OPERATOR, false))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(spaceTradersClient, repository, systemCache);
    }

    // ── anonymous callers (auth-design.md decisions 2 and 3) ──────────────────────
    // No credential is a legitimate request, not a rejected one: a cache hit needs nothing
    // at all, and only a miss (nothing to serve without calling upstream) turns into a 401.

    @Test
    void getWaypoint_cacheHit_noTokenAtAll_stillSucceeds() {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.of(waypointEntity()));

        JsonNode result = service.getWaypoint(SYMBOL, null, false);

        assertThat(result.path("symbol").asText()).isEqualTo(SYMBOL);
        verifyNoInteractions(spaceTradersClient);
    }

    @Test
    void getWaypoint_cacheMiss_noToken_throwsUnauthorizedWithoutCallingUpstream() {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getWaypoint(SYMBOL, null, false))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(spaceTradersClient);
    }

    // ── forceRefresh path ────────────────────────────────────────────────────

    @Test
    void getWaypoint_forceRefresh_bypassesCacheAndFetchesUpstream() throws Exception {
        when(spaceTradersClient.fetchWaypoint(SYSTEM, SYMBOL))
                .thenReturn(upstreamWaypoint());

        service.getWaypoint(SYMBOL, OPERATOR, true);

        verify(repository, never()).findBySymbol(any());
        verify(spaceTradersClient).fetchWaypoint(SYSTEM, SYMBOL);
    }

    @Test
    void refreshWaypointsBySystem_deletesExistingAndRefetchesAll() throws Exception {
        when(spaceTradersClient.fetchWaypointsBySystem(SYSTEM))
                .thenReturn(List.of(upstreamWaypoint()));

        List<JsonNode> result = service.refreshWaypointsBySystem(SYSTEM, OPERATOR);

        assertThat(result).hasSize(1);
        verify(systemCache, never()).isComplete(any());
        verify(repository).deleteBySystemSymbol(SYSTEM);
        verify(repository).upsert(any(WaypointEntity.class));
        verify(systemCache).markComplete(SYSTEM);
    }

    /**
     * Regression: rows were mapped and written one at a time, so an unusable waypoint part
     * way through a system response left the cache already emptied and half re-filled.
     * Mapping now happens before the first write.
     */
    @Test
    void refreshWaypointsBySystem_unusableWaypointInResponse_leavesTheCacheUntouched() throws Exception {
        when(spaceTradersClient.fetchWaypointsBySystem(SYSTEM))
                .thenReturn(List.of(upstreamWaypoint(), objectMapper.readTree("""
                        {"type":"MOON"}""")));

        assertThatThrownBy(() ->
                service.refreshWaypointsBySystem(SYSTEM, OPERATOR))
                .isInstanceOf(ApiException.class);

        verify(repository, never()).deleteBySystemSymbol(any());
        verify(repository, never()).upsert(any());
        verify(systemCache, never()).markComplete(any());
    }

    // ── auth and priority propagation ────────────────────────────────────────

    @Test
    void getWaypoint_cacheMiss_callsClientWithSystemAndSymbolOnly() throws Exception {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.empty());
        when(spaceTradersClient.fetchWaypoint(anyString(), anyString()))
                .thenReturn(upstreamWaypoint());

        service.getWaypoint(SYMBOL, OPERATOR, false);

        verify(spaceTradersClient).fetchWaypoint(SYSTEM, SYMBOL);
    }

    // ── upstream error propagation ───────────────────────────────────────────

    @Test
    void getWaypoint_upstreamReturns404_propagatesNotFound() {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.empty());
        when(spaceTradersClient.fetchWaypoint(any(), any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Not found"));

        assertThatThrownBy(() -> service.getWaypoint(SYMBOL, OPERATOR, false))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode upstreamWaypoint() throws Exception {
        return objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","systemSymbol":"X1-FQ86","x":10,"y":20}""");
    }

    private WaypointEntity waypointEntity() {
        return new WaypointEntity(SYMBOL, SYSTEM, "ASTEROID", 10, 20, """
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","systemSymbol":"X1-FQ86","x":10,"y":20}""",
                "2024-01-01T00:00:00Z");
    }
}
