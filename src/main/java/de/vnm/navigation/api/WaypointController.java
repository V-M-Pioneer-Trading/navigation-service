package de.vnm.navigation.api;

import com.fasterxml.jackson.databind.JsonNode;
import de.vnm.navigation.service.WaypointService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for waypoint data.
 *
 * <p>All endpoints accept an {@code Authorization: Bearer <token>} header that is
 * forwarded to the SpaceTraders API when an upstream fetch is needed. The token is
 * never stored by this service.
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Waypoints", description = "Waypoint lookup and cache management")
public class WaypointController {

    private final WaypointService waypointService;

    public WaypointController(WaypointService waypointService) {
        this.waypointService = waypointService;
    }

    // ── Single waypoint ───────────────────────────────────────────────────────

    @Operation(
        summary = "Get waypoint by symbol",
        description = """
            Returns a waypoint. Serves from local cache if available; fetches from SpaceTraders \
            on first miss. Use `forceRefresh=true` to bypass the cache."""
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Waypoint data"),
        @ApiResponse(responseCode = "401", description = "Invalid or missing SpaceTraders token",
                     content = @Content),
        @ApiResponse(responseCode = "404", description = "Waypoint not found in SpaceTraders",
                     content = @Content),
        @ApiResponse(responseCode = "502", description = "SpaceTraders upstream error",
                     content = @Content)
    })
    @GetMapping("/waypoints/{symbol}")
    public ResponseEntity<JsonNode> getWaypoint(
            @Parameter(description = "Waypoint symbol, e.g. X1-FQ86-B29")
            @PathVariable String symbol,
            @Parameter(description = "Bypass cache and re-fetch from SpaceTraders")
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Priority", required = false) String priority) {

        JsonNode data = waypointService.getWaypoint(symbol, authorization, priority, forceRefresh);
        return ResponseEntity.ok(data);
    }

    @Operation(
        summary = "Refresh a single waypoint",
        description = "Force-fetches the waypoint from SpaceTraders and updates the local cache."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Updated waypoint data"),
        @ApiResponse(responseCode = "401", description = "Invalid token", content = @Content),
        @ApiResponse(responseCode = "404", description = "Waypoint not found", content = @Content),
        @ApiResponse(responseCode = "502", description = "Upstream error", content = @Content)
    })
    @PostMapping("/waypoints/{symbol}/refresh")
    public ResponseEntity<JsonNode> refreshWaypoint(
            @Parameter(description = "Waypoint symbol, e.g. X1-FQ86-B29")
            @PathVariable String symbol,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Priority", required = false) String priority) {

        JsonNode data = waypointService.refreshWaypoint(symbol, authorization, priority);
        return ResponseEntity.ok(data);
    }

    // ── System waypoints ──────────────────────────────────────────────────────

    @Operation(
        summary = "List waypoints by system",
        description = """
            Returns all waypoints for a system. Serves from local cache if any data exists; \
            fetches all pages from SpaceTraders on first miss. Use `forceRefresh=true` to \
            bypass the cache and re-fetch all waypoints for the system."""
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of waypoints"),
        @ApiResponse(responseCode = "401", description = "Invalid token", content = @Content),
        @ApiResponse(responseCode = "404", description = "System not found", content = @Content),
        @ApiResponse(responseCode = "502", description = "Upstream error", content = @Content)
    })
    @GetMapping("/systems/{systemSymbol}/waypoints")
    public ResponseEntity<Map<String, Object>> getWaypointsBySystem(
            @Parameter(description = "System symbol, e.g. X1-FQ86")
            @PathVariable String systemSymbol,
            @Parameter(description = "Bypass cache and re-fetch all waypoints for the system")
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Priority", required = false) String priority) {

        List<JsonNode> waypoints =
                waypointService.getWaypointsBySystem(systemSymbol, authorization, priority, forceRefresh);
        return ResponseEntity.ok(Map.of("data", waypoints, "total", waypoints.size()));
    }

    @Operation(
        summary = "Refresh all waypoints for a system",
        description = "Force-fetches all waypoints for a system from SpaceTraders and replaces the local cache."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Updated list of waypoints"),
        @ApiResponse(responseCode = "401", description = "Invalid token", content = @Content),
        @ApiResponse(responseCode = "502", description = "Upstream error", content = @Content)
    })
    @PostMapping("/systems/{systemSymbol}/waypoints/refresh")
    public ResponseEntity<Map<String, Object>> refreshWaypointsBySystem(
            @Parameter(description = "System symbol, e.g. X1-FQ86")
            @PathVariable String systemSymbol,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "X-Priority", required = false) String priority) {

        List<JsonNode> waypoints =
                waypointService.refreshWaypointsBySystem(systemSymbol, authorization, priority);
        return ResponseEntity.ok(Map.of("data", waypoints, "total", waypoints.size()));
    }
}
