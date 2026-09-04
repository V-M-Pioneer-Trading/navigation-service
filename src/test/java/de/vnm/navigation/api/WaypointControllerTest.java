package de.vnm.navigation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.client.Priority;
import de.vnm.navigation.exception.ApiException;
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
@Import(GlobalExceptionHandler.class)
class WaypointControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean WaypointService waypointService;

    private static final String TOKEN = "test-token";
    private static final String SYMBOL = "X1-FQ86-B29";
    private static final String SYSTEM = "X1-FQ86";

    // ── GET /api/navigation/v1/waypoints/{symbol} ────────────────────────────────────────

    @Test
    void getWaypoint_returns200WithData() throws Exception {
        when(waypointService.getWaypoint(SYMBOL, TOKEN, Priority.BACKGROUND, false))
                .thenReturn(waypointJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN))
               .andExpect(status().isOk())
               .andExpect(content().contentType(MediaType.APPLICATION_JSON))
               .andExpect(jsonPath("$.symbol").value(SYMBOL));
    }

    @Test
    void getWaypoint_forceRefresh_passedToService() throws Exception {
        when(waypointService.getWaypoint(SYMBOL, TOKEN, Priority.BACKGROUND, true))
                .thenReturn(waypointJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .param("forceRefresh", "true")
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN))
               .andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, TOKEN, Priority.BACKGROUND, true);
    }

    @Test
    void getWaypoint_upstreamReturns404_propagatesNotFound() throws Exception {
        when(waypointService.getWaypoint(any(), any(), any(), anyBoolean()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "Waypoint not found"));

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN))
               .andExpect(status().isNotFound());
    }

    @Test
    void getWaypoint_upstreamReturns401_propagatesUnauthorized() throws Exception {
        when(waypointService.getWaypoint(any(), any(), any(), anyBoolean()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "Invalid token"));

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN))
               .andExpect(status().isUnauthorized());
    }

    @Test
    void getWaypoint_malformedSymbol_becomes400() throws Exception {
        when(waypointService.getWaypoint(any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalArgumentException("Invalid waypoint symbol: NOSEPARATOR"));

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", "NOSEPARATOR")
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN))
               .andExpect(status().isBadRequest());
    }

    // ── anonymous requests (auth-design.md decision 18) ──────────────────────────────────
    // No X-SpaceTraders-Token at all is a legitimate request, not a rejected one — the
    // controller must pass a null credential through rather than 400 on a missing header,
    // and the service decides cache-hit-vs-401 from there.

    @Test
    void getWaypoint_noTokenAtAll_stillReachesTheService() throws Exception {
        when(waypointService.getWaypoint(SYMBOL, null, Priority.BACKGROUND, false))
                .thenReturn(waypointJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.symbol").value(SYMBOL));
    }

    @Test
    void getWaypoint_noTokenAndCacheMiss_propagatesUnauthorizedFromService() throws Exception {
        when(waypointService.getWaypoint(eq(SYMBOL), isNull(), any(), anyBoolean()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "no cached data and no credential"));

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL))
               .andExpect(status().isUnauthorized());
    }

    // ── POST /api/navigation/v1/waypoints/{symbol}/refresh ───────────────────────────────

    @Test
    void refreshWaypoint_returns200WithUpdatedData() throws Exception {
        when(waypointService.refreshWaypoint(SYMBOL, TOKEN, Priority.BACKGROUND))
                .thenReturn(waypointJson());

        mockMvc.perform(post("/api/navigation/v1/waypoints/{symbol}/refresh", SYMBOL)
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.symbol").value(SYMBOL));
    }

    // ── GET /api/navigation/v1/systems/{systemSymbol}/waypoints ──────────────────────────

    @Test
    void getWaypointsBySystem_returns200WithList() throws Exception {
        JsonNode w2 = objectMapper.readTree("""
                {"symbol":"X1-FQ86-A1","type":"PLANET","x":-5,"y":3}""");
        when(waypointService.getWaypointsBySystem(SYSTEM, TOKEN, Priority.BACKGROUND, false))
                .thenReturn(List.of(waypointJson(), w2));

        mockMvc.perform(get("/api/navigation/v1/systems/{systemSymbol}/waypoints", SYSTEM)
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.total").value(2))
               .andExpect(jsonPath("$.data[0].symbol").value("X1-FQ86-B29"))
               .andExpect(jsonPath("$.data[1].symbol").value("X1-FQ86-A1"));
    }

    @Test
    void getWaypointsBySystem_forceRefresh_passedToService() throws Exception {
        when(waypointService.getWaypointsBySystem(SYSTEM, TOKEN, Priority.BACKGROUND, true))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/navigation/v1/systems/{systemSymbol}/waypoints", SYSTEM)
                        .param("forceRefresh", "true")
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN))
               .andExpect(status().isOk());

        verify(waypointService).getWaypointsBySystem(SYSTEM, TOKEN, Priority.BACKGROUND, true);
    }

    // ── POST /api/navigation/v1/systems/{systemSymbol}/waypoints/refresh ─────────────────

    @Test
    void refreshWaypointsBySystem_returns200WithList() throws Exception {
        when(waypointService.refreshWaypointsBySystem(SYSTEM, TOKEN, Priority.BACKGROUND))
                .thenReturn(List.of(waypointJson()));

        mockMvc.perform(post("/api/navigation/v1/systems/{systemSymbol}/waypoints/refresh", SYSTEM)
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.total").value(1));
    }

    // ── X-Priority propagation (meta#37) ──────────────────────────────────────
    // navigation-service used to hardcode X-Priority: interactive on every outbound call,
    // so automation-service's background autopilot traffic jumped st-gateway's queue meant
    // to keep the browser UI responsive. It now forwards what the caller itself declared.

    @Test
    void getWaypoint_forwardsCallersPriorityHeader() throws Exception {
        when(waypointService.getWaypoint(SYMBOL, TOKEN, Priority.INTERACTIVE, false))
                .thenReturn(waypointJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN)
                        .header(ApiHeaders.PRIORITY, "interactive"))
               .andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, TOKEN, Priority.INTERACTIVE, false);
    }

    @Test
    void getWaypoint_missingPriorityHeader_isBackgroundNotInteractive() throws Exception {
        when(waypointService.getWaypoint(SYMBOL, TOKEN, Priority.BACKGROUND, false))
                .thenReturn(waypointJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN))
               .andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, TOKEN, Priority.BACKGROUND, false);
    }

    @Test
    void getWaypoint_unrecognisedPriorityHeader_degradesToBackground() throws Exception {
        when(waypointService.getWaypoint(SYMBOL, TOKEN, Priority.BACKGROUND, false))
                .thenReturn(waypointJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN)
                        .header(ApiHeaders.PRIORITY, "URGENT!!"))
               .andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, TOKEN, Priority.BACKGROUND, false);
    }

    private JsonNode waypointJson() throws Exception {
        return objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","x":10,"y":20}""");
    }
}
