package de.vnm.navigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.auth.Session;
import de.vnm.navigation.auth.TestClerk;
import de.vnm.navigation.client.SpaceTradersClient;
import de.vnm.navigation.repository.WaypointRepository;
import de.vnm.navigation.service.MarketService;
import de.vnm.navigation.service.WaypointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * The cache layer against a real SQLite file, with only the upstream client mocked.
 *
 * <p>This level did not exist before: every other test mocks the repositories, so nothing
 * covered schema.sql, the SQL itself, or transaction boundaries — which is exactly where
 * the cache-completeness and partial-write bugs lived.
 */
@SpringBootTest
class NavigationCacheIntegrationTest {

    private static final Path DATABASE = temporaryDatabase();

    private static final Session OPERATOR = new Session("user_test", java.util.Set.of("universe:refresh"));
    private static final String SYSTEM = "X1-FQ86";
    /** What a controller hands down: the caller's inbound header, forwarded verbatim. */
    private static final String SESSION_HEADER = "Bearer eyJhbGciOiJSUzI1NiJ9.e30.c2ln";

    @DynamicPropertySource
    static void sqliteFile(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        registry.add("clerk.jwt-key", TestClerk::publicKeyPem);
    }

    @MockitoBean SpaceTradersClient spaceTradersClient;
    @MockitoSpyBean WaypointRepository waypointRepository;

    @Autowired WaypointService waypointService;
    @Autowired MarketService marketService;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void emptyTheCache() {
        jdbc.execute("DELETE FROM waypoints");
        jdbc.execute("DELETE FROM system_fetches");
        jdbc.execute("DELETE FROM markets");
        jdbc.execute("DELETE FROM shipyards");
    }

    @Test
    void schemaIsCreatedOnStartup() {
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table'", String.class);

        assertThat(tables).contains("waypoints", "markets", "shipyards", "system_fetches");
    }

    @Test
    void aWalkedSystemIsAfterwardsServedFromDiskWithoutACredential() {
        when(spaceTradersClient.fetchWaypointsBySystem(SYSTEM, SESSION_HEADER))
                .thenReturn(List.of(waypoint("B2"), waypoint("A1")));
        waypointService.getWaypointsBySystem(SYSTEM, OPERATOR, false, SESSION_HEADER);
        reset(spaceTradersClient);

        List<JsonNode> anonymous = waypointService.getWaypointsBySystem(SYSTEM, null, false, null);

        assertThat(anonymous).hasSize(2);
        assertThat(anonymous.stream().map(w -> w.path("symbol").asText()))
                .containsExactly("X1-FQ86-A1", "X1-FQ86-B2");   // ordered by symbol, not insertion
        verifyNoInteractions(spaceTradersClient);
    }

    /**
     * Regression: a listing was served whenever the system had any rows at all. One
     * {@code GET /waypoints/X1-FQ86-A1} put a single row in the table, and from then on the
     * listing endpoint answered "this system has one waypoint" and never walked upstream
     * again — the truncated answer suppressed its own repair.
     */
    @Test
    void aSingleWaypointLookupDoesNotMakeTheSystemLookCached() {
        when(spaceTradersClient.fetchWaypoint(SYSTEM, "X1-FQ86-A1", SESSION_HEADER))
                .thenReturn(waypoint("A1"));
        waypointService.getWaypoint("X1-FQ86-A1", OPERATOR, false, SESSION_HEADER);
        assertThat(cachedSymbols()).containsExactly("X1-FQ86-A1");

        when(spaceTradersClient.fetchWaypointsBySystem(SYSTEM, SESSION_HEADER))
                .thenReturn(List.of(waypoint("A1"), waypoint("B2"), waypoint("C3")));
        List<JsonNode> listing = waypointService.getWaypointsBySystem(SYSTEM, OPERATOR, false, SESSION_HEADER);

        assertThat(listing).hasSize(3);
        verify(spaceTradersClient).fetchWaypointsBySystem(SYSTEM, SESSION_HEADER);
        assertThat(cachedSymbols()).containsExactly("X1-FQ86-A1", "X1-FQ86-B2", "X1-FQ86-C3");
    }

    /**
     * Regression: the refresh deleted the system's rows and then inserted the new ones one
     * statement at a time, with nothing tying them together. A write that failed part-way
     * through left the cache holding a fragment of the new listing and none of the old one,
     * and — worse — that fragment then read as a complete, cached system.
     */
    @Test
    void aRefreshThatFailsPartWayThroughLeavesThePreviousListingIntact() {
        when(spaceTradersClient.fetchWaypointsBySystem(SYSTEM, SESSION_HEADER))
                .thenReturn(List.of(waypoint("A1"), waypoint("B2")));
        waypointService.getWaypointsBySystem(SYSTEM, OPERATOR, false, SESSION_HEADER);
        assertThat(cachedSymbols()).containsExactly("X1-FQ86-A1", "X1-FQ86-B2");

        when(spaceTradersClient.fetchWaypointsBySystem(SYSTEM, SESSION_HEADER))
                .thenReturn(List.of(waypoint("C3"), waypoint("D4")));
        doThrow(new DataIntegrityViolationException("simulated write failure"))
                .when(waypointRepository).upsert(argThat(e -> "X1-FQ86-D4".equals(e.getSymbol())));

        assertThatThrownBy(() ->
                waypointService.refreshWaypointsBySystem(SYSTEM, OPERATOR, SESSION_HEADER))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(cachedSymbols()).containsExactly("X1-FQ86-A1", "X1-FQ86-B2");
    }

    @Test
    void marketRowsSurviveARoundTripThroughSqlite() throws Exception {
        JsonNode upstream = objectMapper.readTree("""
                {"symbol":"X1-FQ86-A1","tradeGoods":[{"symbol":"FUEL","purchasePrice":100}]}""");
        when(spaceTradersClient.fetchMarket(SYSTEM, "X1-FQ86-A1", SESSION_HEADER))
                .thenReturn(upstream);

        marketService.get("X1-FQ86-A1", OPERATOR, false, SESSION_HEADER);
        reset(spaceTradersClient);
        JsonNode fromCache = marketService.get("X1-FQ86-A1", null, false, null);

        assertThat(fromCache.path("tradeGoods").get(0).path("purchasePrice").asInt()).isEqualTo(100);
        verifyNoInteractions(spaceTradersClient);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private List<String> cachedSymbols() {
        return jdbc.queryForList("SELECT symbol FROM waypoints ORDER BY symbol", String.class);
    }

    private JsonNode waypoint(String id) {
        try {
            return objectMapper.readTree("""
                    {"symbol":"X1-FQ86-%s","systemSymbol":"X1-FQ86","type":"ASTEROID","x":1,"y":2}"""
                    .formatted(id));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path temporaryDatabase() {
        try {
            Path directory = Files.createTempDirectory("navigation-service-it");
            directory.toFile().deleteOnExit();
            Path database = directory.resolve("nav.db");
            database.toFile().deleteOnExit();
            return database;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
