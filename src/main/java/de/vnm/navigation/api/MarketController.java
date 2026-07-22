package de.vnm.navigation.api;

import com.fasterxml.jackson.databind.JsonNode;
import de.vnm.navigation.service.MarketService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Market", description = "Market lookup and cache management")
public class MarketController {

    private final MarketService marketService;

    public MarketController(MarketService marketService) {
        this.marketService = marketService;
    }

    @Operation(
        summary = "Get market data for a waypoint",
        description = """
            Returns market data (imports/exports/exchange, prices). Serves from local cache if \
            fetched within the last 60 seconds; fetches fresh from SpaceTraders otherwise. Use \
            `forceRefresh=true` to always bypass the cache."""
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Market data"),
        @ApiResponse(responseCode = "401", description = "Invalid or missing SpaceTraders token",
                     content = @Content),
        @ApiResponse(responseCode = "404", description = "Waypoint has no marketplace",
                     content = @Content),
        @ApiResponse(responseCode = "502", description = "SpaceTraders upstream error",
                     content = @Content)
    })
    @GetMapping("/waypoints/{symbol}/market")
    public ResponseEntity<JsonNode> getMarket(
            @Parameter(description = "Waypoint symbol, e.g. X1-FQ86-B29")
            @PathVariable String symbol,
            @Parameter(description = "Bypass cache and re-fetch from SpaceTraders")
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Priority", required = false) String priority) {

        JsonNode data = marketService.getMarket(symbol, authorization, priority, forceRefresh);
        return ResponseEntity.ok(data);
    }

    @Operation(
        summary = "Refresh market data for a waypoint",
        description = "Force-fetches market data from SpaceTraders and updates the local cache."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Updated market data"),
        @ApiResponse(responseCode = "401", description = "Invalid token", content = @Content),
        @ApiResponse(responseCode = "404", description = "Waypoint has no marketplace", content = @Content),
        @ApiResponse(responseCode = "502", description = "Upstream error", content = @Content)
    })
    @PostMapping("/waypoints/{symbol}/market/refresh")
    public ResponseEntity<JsonNode> refreshMarket(
            @Parameter(description = "Waypoint symbol, e.g. X1-FQ86-B29")
            @PathVariable String symbol,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Priority", required = false) String priority) {

        JsonNode data = marketService.refreshMarket(symbol, authorization, priority);
        return ResponseEntity.ok(data);
    }
}
