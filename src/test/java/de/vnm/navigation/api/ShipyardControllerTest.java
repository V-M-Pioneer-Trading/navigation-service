package de.vnm.navigation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.client.Priority;
import de.vnm.navigation.exception.ApiException;
import de.vnm.navigation.exception.GlobalExceptionHandler;
import de.vnm.navigation.service.ShipyardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ShipyardController.class)
@Import(GlobalExceptionHandler.class)
class ShipyardControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean ShipyardService shipyardService;

    private static final String TOKEN = "test-token";
    private static final String SYMBOL = "X1-FQ86-B29";

    @Test
    void getShipyard_returns200WithData() throws Exception {
        when(shipyardService.get(SYMBOL, TOKEN, Priority.BACKGROUND, false)).thenReturn(shipyardJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}/shipyard", SYMBOL)
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.symbol").value(SYMBOL));
    }

    @Test
    void getShipyard_forceRefreshAndPriority_passedToService() throws Exception {
        when(shipyardService.get(SYMBOL, TOKEN, Priority.INTERACTIVE, true)).thenReturn(shipyardJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}/shipyard", SYMBOL)
                        .param("forceRefresh", "true")
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN)
                        .header(ApiHeaders.PRIORITY, "interactive"))
               .andExpect(status().isOk());

        verify(shipyardService).get(SYMBOL, TOKEN, Priority.INTERACTIVE, true);
    }

    @Test
    void getShipyard_noToken_stillReachesTheService() throws Exception {
        when(shipyardService.get(SYMBOL, null, Priority.BACKGROUND, false)).thenReturn(shipyardJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}/shipyard", SYMBOL))
               .andExpect(status().isOk());
    }

    @Test
    void getShipyard_waypointHasNoShipyard_propagatesNotFound() throws Exception {
        when(shipyardService.get(any(), any(), any(), anyBoolean()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "no shipyard"));

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}/shipyard", SYMBOL)
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN))
               .andExpect(status().isNotFound());
    }

    @Test
    void refreshShipyard_returns200WithUpdatedData() throws Exception {
        when(shipyardService.refresh(SYMBOL, TOKEN, Priority.BACKGROUND)).thenReturn(shipyardJson());

        mockMvc.perform(post("/api/navigation/v1/waypoints/{symbol}/shipyard/refresh", SYMBOL)
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.symbol").value(SYMBOL));
    }

    private JsonNode shipyardJson() throws Exception {
        return objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","shipTypes":[]}""");
    }
}
