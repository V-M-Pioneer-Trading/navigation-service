package de.vnm.navigation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.client.Priority;
import de.vnm.navigation.exception.ApiException;
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
@Import(GlobalExceptionHandler.class)
class MarketControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean MarketService marketService;

    private static final String TOKEN = "test-token";
    private static final String SYMBOL = "X1-FQ86-B29";

    @Test
    void getMarket_returns200WithData() throws Exception {
        when(marketService.get(SYMBOL, TOKEN, Priority.BACKGROUND, false)).thenReturn(marketJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}/market", SYMBOL)
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.symbol").value(SYMBOL));
    }

    @Test
    void getMarket_forceRefreshAndPriority_passedToService() throws Exception {
        when(marketService.get(SYMBOL, TOKEN, Priority.INTERACTIVE, true)).thenReturn(marketJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}/market", SYMBOL)
                        .param("forceRefresh", "true")
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN)
                        .header(ApiHeaders.PRIORITY, "interactive"))
               .andExpect(status().isOk());

        verify(marketService).get(SYMBOL, TOKEN, Priority.INTERACTIVE, true);
    }

    @Test
    void getMarket_noToken_stillReachesTheService() throws Exception {
        when(marketService.get(SYMBOL, null, Priority.BACKGROUND, false)).thenReturn(marketJson());

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}/market", SYMBOL))
               .andExpect(status().isOk());
    }

    @Test
    void getMarket_waypointHasNoMarketplace_propagatesNotFound() throws Exception {
        when(marketService.get(any(), any(), any(), anyBoolean()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "no marketplace"));

        mockMvc.perform(get("/api/navigation/v1/waypoints/{symbol}/market", SYMBOL)
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN))
               .andExpect(status().isNotFound());
    }

    @Test
    void refreshMarket_returns200WithUpdatedData() throws Exception {
        when(marketService.refresh(SYMBOL, TOKEN, Priority.BACKGROUND)).thenReturn(marketJson());

        mockMvc.perform(post("/api/navigation/v1/waypoints/{symbol}/market/refresh", SYMBOL)
                        .header(ApiHeaders.SPACETRADERS_TOKEN, TOKEN))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.symbol").value(SYMBOL));
    }

    private JsonNode marketJson() throws Exception {
        return objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","tradeGoods":[]}""");
    }
}
