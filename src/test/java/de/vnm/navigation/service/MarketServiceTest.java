package de.vnm.navigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.auth.Session;
import de.vnm.navigation.client.SpaceTradersClient;
import de.vnm.navigation.exception.ApiException;
import de.vnm.navigation.model.LocationDataEntity;
import de.vnm.navigation.repository.MarketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MarketServiceTest {

    @Mock MarketRepository repository;
    @Mock SpaceTradersClient spaceTradersClient;

    MarketService service;
    ObjectMapper objectMapper = new ObjectMapper();

    private static final Session OPERATOR = new Session("user_test", java.util.Set.of("universe:refresh"));
    private static final String SYMBOL = "X1-FQ86-B29";
    private static final String SYSTEM = "X1-FQ86";
    private static final Duration TTL = Duration.ofSeconds(60);

    @BeforeEach
    void setUp() {
        service = new MarketService(repository, spaceTradersClient, objectMapper, TTL);
    }

    @Test
    void get_freshCache_returnsDataWithoutCallingUpstream() {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.of(marketEntity(secondsAgo(10))));

        JsonNode result = service.get(SYMBOL, OPERATOR, false);

        assertThat(result.path("symbol").asText()).isEqualTo(SYMBOL);
        verifyNoInteractions(spaceTradersClient);
    }

    @Test
    void get_staleCache_refetchesFromUpstream() throws Exception {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.of(marketEntity(secondsAgo(120))));
        when(spaceTradersClient.fetchMarket(SYSTEM, SYMBOL))
                .thenReturn(upstreamMarket());

        JsonNode result = service.get(SYMBOL, OPERATOR, false);

        assertThat(result.path("symbol").asText()).isEqualTo(SYMBOL);
        verify(spaceTradersClient).fetchMarket(SYSTEM, SYMBOL);
    }

    /**
     * Regression: staleness was decided by a bare {@code Instant.parse} of the stored
     * timestamp. A row whose {@code fetched_at} could not be parsed — written by an older
     * build, or edited by hand — threw {@code DateTimeParseException} out of the cache
     * lookup, which nothing handled, so that waypoint answered 500 on every request and no
     * amount of {@code forceRefresh} on the GET path could clear it.
     */
    @Test
    void get_unreadableFetchedAt_isTreatedAsStaleInsteadOfFailing() throws Exception {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.of(marketEntity("not-a-timestamp")));
        when(spaceTradersClient.fetchMarket(SYSTEM, SYMBOL))
                .thenReturn(upstreamMarket());

        JsonNode result = service.get(SYMBOL, OPERATOR, false);

        assertThat(result.path("symbol").asText()).isEqualTo(SYMBOL);
        verify(spaceTradersClient).fetchMarket(SYSTEM, SYMBOL);
    }

    @Test
    void get_cacheMiss_fetchesFromUpstreamAndStores() throws Exception {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.empty());
        when(spaceTradersClient.fetchMarket(SYSTEM, SYMBOL))
                .thenReturn(upstreamMarket());

        service.get(SYMBOL, OPERATOR, false);

        ArgumentCaptor<LocationDataEntity> captor = ArgumentCaptor.forClass(LocationDataEntity.class);
        verify(repository).upsert(captor.capture());
        assertThat(captor.getValue().getSymbol()).isEqualTo(SYMBOL);
        assertThat(captor.getValue().getSystemSymbol()).isEqualTo(SYSTEM);
    }

    @Test
    void get_forceRefresh_bypassesCacheAndFetchesUpstream() throws Exception {
        when(spaceTradersClient.fetchMarket(SYSTEM, SYMBOL))
                .thenReturn(upstreamMarket());

        service.get(SYMBOL, OPERATOR, true);

        verify(repository, never()).findBySymbol(any());
        verify(spaceTradersClient).fetchMarket(SYSTEM, SYMBOL);
    }

    /**
     * Regression: a zero TTL was only "always stale" by arithmetic — {@code isFresh} asked
     * whether the row predated {@code now - 0}, so a row stamped at or after now (a clock
     * stepped backwards, a restored database) read as a cache hit on a service explicitly
     * configured not to cache.
     */
    @Test
    void zeroTtl_futureDatedRow_isStillRefetched() throws Exception {
        MarketService alwaysStale = new MarketService(repository, spaceTradersClient, objectMapper, Duration.ZERO);
        when(repository.findBySymbol(SYMBOL))
                .thenReturn(Optional.of(marketEntity(Instant.now().plusSeconds(60).toString())));
        when(spaceTradersClient.fetchMarket(SYSTEM, SYMBOL))
                .thenReturn(upstreamMarket());

        alwaysStale.get(SYMBOL, OPERATOR, false);

        verify(spaceTradersClient).fetchMarket(SYSTEM, SYMBOL);
    }

    /** A zero TTL means "never serve from cache", not "cache forever". */
    @Test
    void zeroTtl_alwaysRefetches() throws Exception {
        MarketService alwaysStale = new MarketService(repository, spaceTradersClient, objectMapper, Duration.ZERO);
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.of(marketEntity(secondsAgo(1))));
        when(spaceTradersClient.fetchMarket(SYSTEM, SYMBOL))
                .thenReturn(upstreamMarket());

        alwaysStale.get(SYMBOL, OPERATOR, false);

        verify(spaceTradersClient).fetchMarket(SYSTEM, SYMBOL);
    }

    @Test
    void negativeTtl_isRejectedAtConstruction() {
        assertThatThrownBy(() ->
                new MarketService(repository, spaceTradersClient, objectMapper, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── anonymous callers (auth-design.md decision 18) ──────────────────────

    @Test
    void get_cacheHit_noTokenAtAll_stillSucceeds() {
        when(repository.findBySymbol(SYMBOL)).thenReturn(Optional.of(marketEntity(secondsAgo(10))));

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

    @Test
    void get_malformedSymbol_rejectedBeforeTouchingTheCache() {
        assertThatThrownBy(() -> service.get("NOSEPARATOR", OPERATOR, false))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository, spaceTradersClient);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private JsonNode upstreamMarket() throws Exception {
        return objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","tradeGoods":[]}""");
    }

    private static String secondsAgo(int seconds) {
        return Instant.now().minusSeconds(seconds).toString();
    }

    private LocationDataEntity marketEntity(String fetchedAt) {
        return new LocationDataEntity(SYMBOL, SYSTEM, """
                {"symbol":"X1-FQ86-B29","tradeGoods":[]}""", fetchedAt);
    }
}
