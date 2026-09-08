package de.vnm.navigation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.auth.Session;
import de.vnm.navigation.auth.TestClerk;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import de.vnm.navigation.exception.ApiException;
import de.vnm.navigation.config.ClerkConfig;
import de.vnm.navigation.exception.GlobalExceptionHandler;
import de.vnm.navigation.service.WaypointService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WaypointController.class)
@Import({GlobalExceptionHandler.class, ClerkConfig.class})
class WaypointControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean WaypointService waypointService;

    /** What TestClerk.bearer() verifies to, so mocks can match on the exact session. */
    private static final Session OPERATOR = TestClerk.OPERATOR;

    @DynamicPropertySource
    static void trustAnchor(DynamicPropertyRegistry registry) {
        registry.add("clerk.jwt-key", TestClerk::publicKeyPem);
    }

        private static final String SYMBOL = "X1-FQ86-B29";
    private static final String SYSTEM = "X1-FQ86";

    // ── GET /api/navigation/v1/waypoints/{symbol} ────────────────────────────────────────

    @Test
    void getWaypoint_returns200WithData() throws Exception {
        when(waypointService.getWaypoint(SYMBOL, OPERATOR, false))
                .thenReturn(waypointJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .header("Authorization", TestClerk.bearer()))
               .andExpect(status().isOk())
               .andExpect(content().contentType(MediaType.APPLICATION_JSON))
               .andExpect(jsonPath("$.symbol").value(SYMBOL));
    }

    @Test
    void getWaypoint_forceRefresh_passedToService() throws Exception {
        when(waypointService.getWaypoint(SYMBOL, OPERATOR, true))
                .thenReturn(waypointJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .param("forceRefresh", "true")
                        .header("Authorization", TestClerk.bearer()))
               .andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, OPERATOR, true);
    }

    @Test
    void getWaypoint_upstreamReturns404_propagatesNotFound() throws Exception {
        when(waypointService.getWaypoint(any(), any(), anyBoolean()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Waypoint not found"));

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .header("Authorization", TestClerk.bearer()))
               .andExpect(status().isNotFound());
    }

    /**
     * The relay is only worth anything if it survives the last hop. A gateway 503 has to
     * reach the caller as a 503 carrying the gateway's own sentence - a 502 with the body
     * dropped is what this used to be, and it reads as a transient outage rather than as
     * a credential an operator has to configure.
     */
    @Test
    void getWaypoint_gatewaySays503_relaysTheStatusAndTheSentence() throws Exception {
        when(waypointService.getWaypoint(any(), any(), anyBoolean()))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SpaceTraders credential not configured"));

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .header("Authorization", TestClerk.bearer()))
               .andExpect(status().isServiceUnavailable())
               .andExpect(jsonPath("$.detail").value("SpaceTraders credential not configured"));
    }

    /**
     * st-gateway forwards pacing headers on a passed-through 429 so a caller can back off
     * rather than hammer the shared budget. Relaying the status without them keeps the
     * news and drops the instructions.
     */
    @Test
    void getWaypoint_gatewaySays429_relaysThePacingHeaders() throws Exception {
        when(waypointService.getWaypoint(any(), any(), anyBoolean()))
                .thenThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS, "You have reached your API limit.",
                        java.util.Map.of("Retry-After", "3")));

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .header("Authorization", TestClerk.bearer()))
               .andExpect(status().isTooManyRequests())
               .andExpect(header().string("Retry-After", "3"));
    }

    @Test
    void getWaypoint_upstreamReturns401_propagatesUnauthorized() throws Exception {
        when(waypointService.getWaypoint(any(), any(), anyBoolean()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "Invalid token"));

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .header("Authorization", TestClerk.bearer()))
               .andExpect(status().isUnauthorized());
    }

    @Test
    void getWaypoint_malformedSymbol_becomes400() throws Exception {
        when(waypointService.getWaypoint(any(), any(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("Invalid waypoint symbol: NOSEPARATOR"));

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", "NOSEPARATOR")
                        .header("Authorization", TestClerk.bearer()))
               .andExpect(status().isBadRequest());
    }

    // ── anonymous requests (auth-design.md decision 18) ──────────────────────────────────
    // No X-SpaceTraders-Token at all is a legitimate request, not a rejected one — the
    // controller must pass a null credential through rather than 400 on a missing header,
    // and the service decides cache-hit-vs-401 from there.

    @Test
    void getWaypoint_noTokenAtAll_stillReachesTheService() throws Exception {
        when(waypointService.getWaypoint(SYMBOL, null, false))
                .thenReturn(waypointJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.symbol").value(SYMBOL));
    }

    @Test
    void getWaypoint_noTokenAndCacheMiss_propagatesUnauthorizedFromService() throws Exception {
        when(waypointService.getWaypoint(eq(SYMBOL), isNull(), anyBoolean()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "no cached data and no credential"));

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL))
               .andExpect(status().isUnauthorized());
    }

    // ── POST /api/navigation/v1/waypoints/{symbol}/refresh ───────────────────────────────

    @Test
    void refreshWaypoint_returns200WithUpdatedData() throws Exception {
        when(waypointService.refreshWaypoint(SYMBOL, OPERATOR))
                .thenReturn(waypointJson());

        mockMvc.perform(post("/api/navigation/v1/waypoints/{symbol}/refresh", SYMBOL)
                        .header("Authorization", TestClerk.bearer()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.symbol").value(SYMBOL));
    }

    // ── GET /api/navigation/v1/systems/{systemSymbol}/waypoints ──────────────────────────

    @Test
    void getWaypointsBySystem_returns200WithList() throws Exception {
        JsonNode w2 = objectMapper.readTree("""
                {"symbol":"X1-FQ86-A1","type":"PLANET","x":-5,"y":3}""");
        when(waypointService.getWaypointsBySystem(SYSTEM, OPERATOR, false))
                .thenReturn(List.of(waypointJson(), w2));

        mockMvc.perform(get("/api/navigation/v1/systems/{systemSymbol}/waypoints", SYSTEM)
                        .header("Authorization", TestClerk.bearer()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.total").value(2))
               .andExpect(jsonPath("$.data[0].symbol").value("X1-FQ86-B29"))
               .andExpect(jsonPath("$.data[1].symbol").value("X1-FQ86-A1"));
    }

    @Test
    void getWaypointsBySystem_forceRefresh_passedToService() throws Exception {
        when(waypointService.getWaypointsBySystem(SYSTEM, OPERATOR, true))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/navigation/v1/systems/{systemSymbol}/waypoints", SYSTEM)
                        .param("forceRefresh", "true")
                        .header("Authorization", TestClerk.bearer()))
               .andExpect(status().isOk());

        verify(waypointService).getWaypointsBySystem(SYSTEM, OPERATOR, true);
    }

    // ── POST /api/navigation/v1/systems/{systemSymbol}/waypoints/refresh ─────────────────

    @Test
    void refreshWaypointsBySystem_returns200WithList() throws Exception {
        when(waypointService.refreshWaypointsBySystem(SYSTEM, OPERATOR))
                .thenReturn(List.of(waypointJson()));

        mockMvc.perform(post("/api/navigation/v1/systems/{systemSymbol}/waypoints/refresh", SYSTEM)
                        .header("Authorization", TestClerk.bearer()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.total").value(1));
    }

    private JsonNode waypointJson() throws Exception {
        return objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","x":10,"y":20}""");
    }
}
