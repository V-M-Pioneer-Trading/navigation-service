package de.vnm.navigation.api;

import com.fasterxml.jackson.databind.JsonNode;
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
@RequestMapping("/api/v1")
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
        @ApiResponse(responseCode = "401", description = "Invalid or missing SpaceTraders token",
                     content = @Content),
        @ApiResponse(responseCode = "404", description = "Waypoint has no shipyard",
                     content = @Content),
        @ApiResponse(responseCode = "502", description = "SpaceTraders upstream error",
                     content = @Content)
    })
    @GetMapping("/waypoints/{symbol}/shipyard")
    public ResponseEntity<JsonNode> getShipyard(
            @Parameter(description = "Waypoint symbol, e.g. X1-FQ86-B29")
            @PathVariable String symbol,
            @Parameter(description = "Bypass cache and re-fetch from SpaceTraders")
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @RequestHeader("Authorization") String authorization) {

        JsonNode data = shipyardService.getShipyard(symbol, authorization, forceRefresh);
        return ResponseEntity.ok(data);
    }

    @Operation(
        summary = "Refresh shipyard data for a waypoint",
        description = "Force-fetches shipyard data from SpaceTraders and updates the local cache."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Updated shipyard data"),
        @ApiResponse(responseCode = "401", description = "Invalid token", content = @Content),
        @ApiResponse(responseCode = "404", description = "Waypoint has no shipyard", content = @Content),
        @ApiResponse(responseCode = "502", description = "Upstream error", content = @Content)
    })
    @PostMapping("/waypoints/{symbol}/shipyard/refresh")
    public ResponseEntity<JsonNode> refreshShipyard(
            @Parameter(description = "Waypoint symbol, e.g. X1-FQ86-B29")
            @PathVariable String symbol,
            @RequestHeader("Authorization") String authorization) {

        JsonNode data = shipyardService.refreshShipyard(symbol, authorization);
        return ResponseEntity.ok(data);
    }
}
