package de.vnm.navigation.api;

import com.fasterxml.jackson.databind.JsonNode;
import de.vnm.navigation.auth.ClerkAuthFilter;
import de.vnm.navigation.auth.Session;
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
@RequestMapping("/api/navigation/v1")
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
        @ApiResponse(responseCode = "400", description = "Malformed waypoint symbol", content = @Content),
        @ApiResponse(responseCode = "401", description = "Not cached and caller is anonymous",
                     content = @Content),
        @ApiResponse(responseCode = "404", description = "Waypoint has no marketplace",
                     content = @Content),
        @ApiResponse(responseCode = "4XX", description = "Any other status st-gateway sent, relayed with its own message and its Retry-After / X-RateLimit-* headers - 429 when the shared rate budget is spent, 401 when the injected agent token was rejected",
                     content = @Content),
        @ApiResponse(responseCode = "503", description = "Relayed from st-gateway: no SpaceTraders credential configured, or auth-service unavailable",
                     content = @Content),
        @ApiResponse(responseCode = "504", description = "st-gateway did not answer",
                     content = @Content),
        @ApiResponse(responseCode = "502", description = "st-gateway answered with something unreadable",
                     content = @Content)
    })
    @GetMapping("/waypoints/{symbol}/market")
    public ResponseEntity<JsonNode> getMarket(
            @Parameter(description = "Waypoint symbol, e.g. X1-FQ86-B29")
            @PathVariable String symbol,
            @Parameter(description = "Bypass cache and re-fetch from SpaceTraders")
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @Parameter(hidden = true)
            @RequestAttribute(value = ClerkAuthFilter.SESSION_ATTRIBUTE, required = false) Session session) {

        return ResponseEntity.ok(marketService.get(symbol, session, forceRefresh));
    }

    @Operation(
        summary = "Refresh market data for a waypoint",
        description = "Force-fetches market data from SpaceTraders and updates the local cache."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Updated market data"),
        @ApiResponse(responseCode = "400", description = "Malformed waypoint symbol", content = @Content),
        @ApiResponse(responseCode = "401", description = "No Clerk session, or the presented one did not verify", content = @Content),
        @ApiResponse(responseCode = "404", description = "Waypoint has no marketplace", content = @Content),
        @ApiResponse(responseCode = "4XX", description = "Any other status st-gateway sent, relayed with its own message and its Retry-After / X-RateLimit-* headers - 429 when the shared rate budget is spent, 401 when the injected agent token was rejected",
                     content = @Content),
        @ApiResponse(responseCode = "503", description = "Relayed from st-gateway: no SpaceTraders credential configured, or auth-service unavailable",
                     content = @Content),
        @ApiResponse(responseCode = "504", description = "st-gateway did not answer",
                     content = @Content),
        @ApiResponse(responseCode = "502", description = "st-gateway answered with something unreadable",
                     content = @Content)
    })
    @PostMapping("/waypoints/{symbol}/market/refresh")
    public ResponseEntity<JsonNode> refreshMarket(
            @Parameter(description = "Waypoint symbol, e.g. X1-FQ86-B29")
            @PathVariable String symbol,
            @Parameter(hidden = true)
            @RequestAttribute(value = ClerkAuthFilter.SESSION_ATTRIBUTE, required = false) Session session) {

        return ResponseEntity.ok(marketService.refresh(symbol, session));
    }
}
