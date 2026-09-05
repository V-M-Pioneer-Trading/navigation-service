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
@Import({GlobalExceptionHandler.class, ClerkConfig.class})
class ShipyardControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean ShipyardService shipyardService;

    /** What TestClerk.bearer() verifies to, so mocks can match on the exact session. */
    private static final Session OPERATOR = TestClerk.OPERATOR;

    @DynamicPropertySource
    static void trustAnchor(DynamicPropertyRegistry registry) {
        registry.add("clerk.jwt-key", TestClerk::publicKeyPem);
    }

        private static final String SYMBOL = "X1-FQ86-B29";

    @Test
    void getShipyard_returns200WithData() throws Exception {
        when(shipyardService.get(SYMBOL, OPERATOR, false)).thenReturn(shipyardJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}/shipyard", SYMBOL)
                        .header("Authorization", TestClerk.bearer()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.symbol").value(SYMBOL));
    }

    @Test
    void getShipyard_forceRefresh_passedToService() throws Exception {
        when(shipyardService.get(SYMBOL, OPERATOR, true)).thenReturn(shipyardJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}/shipyard", SYMBOL)
                        .param("forceRefresh", "true")
                        .header("Authorization", TestClerk.bearer()))
               .andExpect(status().isOk());

        verify(shipyardService).get(SYMBOL, OPERATOR, true);
    }

    @Test
    void getShipyard_noToken_stillReachesTheService() throws Exception {
        when(shipyardService.get(SYMBOL, null, false)).thenReturn(shipyardJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}/shipyard", SYMBOL))
               .andExpect(status().isOk());
    }

    @Test
    void getShipyard_waypointHasNoShipyard_propagatesNotFound() throws Exception {
        when(shipyardService.get(any(), any(), anyBoolean()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "no shipyard"));

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}/shipyard", SYMBOL)
                        .header("Authorization", TestClerk.bearer()))
               .andExpect(status().isNotFound());
    }

    @Test
    void refreshShipyard_returns200WithUpdatedData() throws Exception {
        when(shipyardService.refresh(SYMBOL, OPERATOR)).thenReturn(shipyardJson());

        mockMvc.perform(post("/api/navigation/v1/waypoints/{symbol}/shipyard/refresh", SYMBOL)
                        .header("Authorization", TestClerk.bearer()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.symbol").value(SYMBOL));
    }

    private JsonNode shipyardJson() throws Exception {
        return objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","shipTypes":[]}""");
    }
}
