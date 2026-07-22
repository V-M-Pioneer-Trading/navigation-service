package de.vnm.navigation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.exception.GlobalExceptionHandler;
import de.vnm.navigation.exception.UpstreamException;
import de.vnm.navigation.service.WaypointService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    @MockBean WaypointService waypointService;

    private static final String AUTH = "Bearer test-token";
    private static final String SYMBOL = "X1-FQ86-B29";
    private static final String SYSTEM = "X1-FQ86";

    // ── GET /api/navigation/v1/waypoints/{symbol} ────────────────────────────────────────

    @Test
    void getWaypoint_returns200WithData() throws Exception {
        JsonNode waypointJson = objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","x":10,"y":20}""");
        when(waypointService.getWaypoint(eq(SYMBOL), eq(AUTH), isNull(), eq(false))).thenReturn(waypointJson);

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .header("Authorization", AUTH))
               .andExpect(status().isOk())
               .andExpect(content().contentType(MediaType.APPLICATION_JSON))
               .andExpect(jsonPath("$.symbol").value(SYMBOL));
    }

    @Test
    void getWaypoint_forceRefresh_passedToService() throws Exception {
        JsonNode waypointJson = objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","x":10,"y":20}""");
        when(waypointService.getWaypoint(eq(SYMBOL), eq(AUTH), isNull(), eq(true))).thenReturn(waypointJson);

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .param("forceRefresh", "true")
                        .header("Authorization", AUTH))
               .andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, AUTH, null, true);
    }

    @Test
    void getWaypoint_upstreamReturns404_propagatesNotFound() throws Exception {
        when(waypointService.getWaypoint(any(), any(), any(), anyBoolean()))
                .thenThrow(new UpstreamException(HttpStatus.NOT_FOUND, "Waypoint not found"));

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .header("Authorization", AUTH))
               .andExpect(status().isNotFound());
    }

    @Test
    void getWaypoint_upstreamReturns401_propagatesUnauthorized() throws Exception {
        when(waypointService.getWaypoint(any(), any(), any(), anyBoolean()))
                .thenThrow(new UpstreamException(HttpStatus.UNAUTHORIZED, "Invalid token"));

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .header("Authorization", AUTH))
               .andExpect(status().isUnauthorized());
    }

    // ── POST /api/navigation/v1/waypoints/{symbol}/refresh ───────────────────────────────

    @Test
    void refreshWaypoint_returns200WithUpdatedData() throws Exception {
        JsonNode waypointJson = objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","x":10,"y":20}""");
        when(waypointService.refreshWaypoint(eq(SYMBOL), eq(AUTH), isNull())).thenReturn(waypointJson);

        mockMvc.perform(post("/api/navigation/v1/waypoints/{symbol}/refresh", SYMBOL)
                        .header("Authorization", AUTH))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.symbol").value(SYMBOL));
    }

    // ── GET /api/navigation/v1/systems/{systemSymbol}/waypoints ──────────────────────────

    @Test
    void getWaypointsBySystem_returns200WithList() throws Exception {
        JsonNode w1 = objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","x":10,"y":20}""");
        JsonNode w2 = objectMapper.readTree("""
                {"symbol":"X1-FQ86-A1","type":"PLANET","x":-5,"y":3}""");
        when(waypointService.getWaypointsBySystem(eq(SYSTEM), eq(AUTH), isNull(), eq(false)))
                .thenReturn(List.of(w1, w2));

        mockMvc.perform(get("/api/navigation/v1/systems/{systemSymbol}/waypoints", SYSTEM)
                        .header("Authorization", AUTH))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.total").value(2))
               .andExpect(jsonPath("$.data[0].symbol").value("X1-FQ86-B29"))
               .andExpect(jsonPath("$.data[1].symbol").value("X1-FQ86-A1"));
    }

    @Test
    void getWaypointsBySystem_forceRefresh_passedToService() throws Exception {
        when(waypointService.getWaypointsBySystem(eq(SYSTEM), eq(AUTH), isNull(), eq(true)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/navigation/v1/systems/{systemSymbol}/waypoints", SYSTEM)
                        .param("forceRefresh", "true")
                        .header("Authorization", AUTH))
               .andExpect(status().isOk());

        verify(waypointService).getWaypointsBySystem(SYSTEM, AUTH, null, true);
    }

    // ── POST /api/navigation/v1/systems/{systemSymbol}/waypoints/refresh ─────────────────

    @Test
    void refreshWaypointsBySystem_returns200WithList() throws Exception {
        JsonNode w1 = objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","x":10,"y":20}""");
        when(waypointService.refreshWaypointsBySystem(eq(SYSTEM), eq(AUTH), isNull()))
                .thenReturn(List.of(w1));

        mockMvc.perform(post("/api/navigation/v1/systems/{systemSymbol}/waypoints/refresh", SYSTEM)
                        .header("Authorization", AUTH))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.total").value(1));
    }

    // ── X-Priority propagation (meta#37) ──────────────────────────────────────
    // navigation-service used to hardcode X-Priority: interactive on every
    // outbound call, so automation-service's background autopilot traffic
    // jumped st-gateway's queue meant to keep the browser UI responsive. It now
    // forwards whatever the caller (command-interface vs automation-service)
    // itself declared.

    @Test
    void getWaypoint_forwardsCallersPriorityHeaderToService() throws Exception {
        JsonNode waypointJson = objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","x":10,"y":20}""");
        when(waypointService.getWaypoint(eq(SYMBOL), eq(AUTH), eq("interactive"), eq(false)))
                .thenReturn(waypointJson);

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .header("Authorization", AUTH)
                        .header("X-Priority", "interactive"))
               .andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, AUTH, "interactive", false);
    }

    @Test
    void getWaypoint_missingPriorityHeader_passesNullNotInteractive() throws Exception {
        JsonNode waypointJson = objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","x":10,"y":20}""");
        when(waypointService.getWaypoint(eq(SYMBOL), eq(AUTH), isNull(), eq(false)))
                .thenReturn(waypointJson);

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}", SYMBOL)
                        .header("Authorization", AUTH))
               .andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, AUTH, null, false);
    }
}
