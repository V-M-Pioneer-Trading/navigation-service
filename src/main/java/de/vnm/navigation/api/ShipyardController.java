package de.vnm.navigation.api;

import com.fasterxml.jackson.databind.JsonNode;
import de.vnm.navigation.auth.ClerkAuthFilter;
import de.vnm.navigation.auth.Session;
import de.vnm.navigation.service.ShipyardService;
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
@Tag(name = "Shipyard", description = "Shipyard lookup and cache management")
public class ShipyardController {

    private final ShipyardService shipyardService;

    public ShipyardController(ShipyardService shipyardService) {
        this.shipyardService = shipyardService;
    }

    @Operation(
        summary = "Get shipyard data for a waypoint",
        description = """
            Returns shipyard data (ships for sale). Serves from local cache if available; \
            fetches from SpaceTraders on first miss. Use `forceRefresh=true` to bypass the cache."""
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Shipyard data"),
        @ApiResponse(responseCode = "400", description = "Malformed waypoint symbol", content = @Content),
        @ApiResponse(responseCode = "401", description = "Not cached and caller is anonymous",
                     content = @Content),
        @ApiResponse(responseCode = "404", description = "Waypoint has no shipyard",
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
    @GetMapping("/waypoints/{symbol}/shipyard")
    public ResponseEntity<JsonNode> getShipyard(
            @Parameter(description = "Waypoint symbol, e.g. X1-FQ86-B29")
            @PathVariable String symbol,
            @Parameter(description = "Bypass cache and re-fetch from SpaceTraders")
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @Parameter(hidden = true)
            @RequestAttribute(value = ClerkAuthFilter.SESSION_ATTRIBUTE, required = false) Session session) {

        return ResponseEntity.ok(shipyardService.get(symbol, session, forceRefresh));
    }

    @Operation(
        summary = "Refresh shipyard data for a waypoint",
        description = "Force-fetches shipyard data from SpaceTraders and updates the local cache."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Updated shipyard data"),
        @ApiResponse(responseCode = "400", description = "Malformed waypoint symbol", content = @Content),
        @ApiResponse(responseCode = "401", description = "No Clerk session, or the presented one did not verify", content = @Content),
        @ApiResponse(responseCode = "404", description = "Waypoint has no shipyard", content = @Content),
        @ApiResponse(responseCode = "4XX", description = "Any other status st-gateway sent, relayed with its own message and its Retry-After / X-RateLimit-* headers - 429 when the shared rate budget is spent, 401 when the injected agent token was rejected",
                     content = @Content),
        @ApiResponse(responseCode = "503", description = "Relayed from st-gateway: no SpaceTraders credential configured, or auth-service unavailable",
                     content = @Content),
        @ApiResponse(responseCode = "504", description = "st-gateway did not answer",
                     content = @Content),
        @ApiResponse(responseCode = "502", description = "st-gateway answered with something unreadable",
                     content = @Content)
    })
    @PostMapping("/waypoints/{symbol}/shipyard/refresh")
    public ResponseEntity<JsonNode> refreshShipyard(
            @Parameter(description = "Waypoint symbol, e.g. X1-FQ86-B29")
            @PathVariable String symbol,
            @Parameter(hidden = true)
            @RequestAttribute(value = ClerkAuthFilter.SESSION_ATTRIBUTE, required = false) Session session) {

        return ResponseEntity.ok(shipyardService.refresh(symbol, session));
    }
}
