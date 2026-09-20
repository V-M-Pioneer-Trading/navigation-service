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
import de.vnm.navigation.service.MarketService;
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

@WebMvcTest(MarketController.class)
@Import({GlobalExceptionHandler.class, ClerkConfig.class})
class MarketControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean MarketService marketService;

    /** What TestClerk.bearer() verifies to, so mocks can match on the exact session. */
    private static final Session OPERATOR = TestClerk.OPERATOR;

    /**
     * One fixed token for the whole class. The controller forwards the caller's inbound
     * header onward verbatim, so a stub matching on these exact bytes is itself the proof
     * that the header reaches the service unchanged.
     */
    private static final String OPERATOR_BEARER = TestClerk.bearer();

    @DynamicPropertySource
    static void trustAnchor(DynamicPropertyRegistry registry) {
        registry.add("clerk.jwt-key", TestClerk::publicKeyPem);
    }

        private static final String SYMBOL = "X1-FQ86-B29";

    @Test
    void getMarket_returns200WithData() throws Exception {
        when(marketService.get(SYMBOL, OPERATOR, false, OPERATOR_BEARER)).thenReturn(marketJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}/market", SYMBOL)
                        .header("Authorization", OPERATOR_BEARER))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.symbol").value(SYMBOL));
    }

    @Test
    void getMarket_forceRefresh_passedToService() throws Exception {
        when(marketService.get(SYMBOL, OPERATOR, true, OPERATOR_BEARER)).thenReturn(marketJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}/market", SYMBOL)
                        .param("forceRefresh", "true")
                        .header("Authorization", OPERATOR_BEARER))
               .andExpect(status().isOk());

        verify(marketService).get(SYMBOL, OPERATOR, true, OPERATOR_BEARER);
    }

    @Test
    void getMarket_noToken_stillReachesTheService() throws Exception {
        when(marketService.get(SYMBOL, null, false, null)).thenReturn(marketJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}/market", SYMBOL))
               .andExpect(status().isOk());
    }

    @Test
    void getMarket_waypointHasNoMarketplace_propagatesNotFound() throws Exception {
        when(marketService.get(any(), any(), anyBoolean(), any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "no marketplace"));

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}/market", SYMBOL)
                        .header("Authorization", OPERATOR_BEARER))
               .andExpect(status().isNotFound());
    }

    @Test
    void refreshMarket_returns200WithUpdatedData() throws Exception {
        when(marketService.refresh(SYMBOL, OPERATOR, OPERATOR_BEARER)).thenReturn(marketJson());

        mockMvc.perform(post("/api/navigation/v1/waypoints/{symbol}/market/refresh", SYMBOL)
                        .header("Authorization", OPERATOR_BEARER))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.symbol").value(SYMBOL));
    }

    private JsonNode marketJson() throws Exception {
        return objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","tradeGoods":[]}""");
    }
}
