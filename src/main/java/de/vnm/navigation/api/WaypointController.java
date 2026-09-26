package de.vnm.navigation.api;

import com.fasterxml.jackson.databind.JsonNode;
import de.vnm.navigation.auth.CallerAttributes;
import de.vnm.navigation.auth.Scopes;
import de.vnm.navigation.auth.Session;
import de.vnm.navigation.introspection.web.AllowPublic;
import de.vnm.navigation.introspection.web.RequireScope;
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
 * <p>Reads are {@link AllowPublic} and served from cache for anonymous callers; a session
 * auth-service verified (handed in as {@link CallerAttributes#SESSION_ATTRIBUTE})
 * additionally allows a live fetch on a miss. The refresh routes are
 * {@link RequireScope @RequireScope(universe:refresh)}, enforced by the introspection
 * interceptor before the controller runs. No SpaceTraders credential is ever presented to
 * this service — st-gateway injects it (auth-design.md decision 5).
 */
@RestController
@RequestMapping("/api/navigation/v1")
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
        @ApiResponse(responseCode = "400", description = "Malformed waypoint symbol", content = @Content),
        @ApiResponse(responseCode = "401", description = "Not cached and caller is anonymous, or auth-service says a presented token is not active",
                     content = @Content),
        @ApiResponse(responseCode = "404", description = "Waypoint not found in SpaceTraders",
                     content = @Content),
        @ApiResponse(responseCode = "4XX", description = "Any other status st-gateway sent, relayed with its own message and its Retry-After / X-RateLimit-* headers - 429 when the shared rate budget is spent, 401 when the injected agent token was rejected",
                     content = @Content),
        @ApiResponse(responseCode = "503", description = "auth-service could not verify a presented token (`the authentication service could not process this request`), or relayed from st-gateway: no SpaceTraders credential configured",
                     content = @Content),
        @ApiResponse(responseCode = "504", description = "st-gateway did not answer",
                     content = @Content),
        @ApiResponse(responseCode = "502", description = "st-gateway answered with something unreadable",
                     content = @Content)
    })
    @AllowPublic
    @GetMapping("/waypoints/{symbol}")
    public ResponseEntity<JsonNode> getWaypoint(
            @Parameter(description = "Waypoint symbol, e.g. X1-FQ86-B29")
            @PathVariable String symbol,
            @Parameter(description = "Bypass cache and re-fetch from SpaceTraders")
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @Parameter(hidden = true)
            @RequestAttribute(value = CallerAttributes.SESSION_ATTRIBUTE, required = false) Session session,
            @Parameter(hidden = true)
            @RequestAttribute(value = CallerAttributes.CALLER_AUTHORIZATION_ATTRIBUTE, required = false)
            String callerAuthorization) {

        return ResponseEntity.ok(
                waypointService.getWaypoint(symbol, session, forceRefresh, callerAuthorization));
    }

    @Operation(
        summary = "Refresh a single waypoint",
        description = "Force-fetches the waypoint from SpaceTraders and updates the local cache."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Updated waypoint data"),
        @ApiResponse(responseCode = "400", description = "Malformed waypoint symbol", content = @Content),
        @ApiResponse(responseCode = "401", description = "No bearer token, or auth-service says the presented one is not active", content = @Content),
        @ApiResponse(responseCode = "403", description = "A verified session without the universe:refresh scope", content = @Content),
        @ApiResponse(responseCode = "404", description = "Waypoint not found", content = @Content),
        @ApiResponse(responseCode = "4XX", description = "Any other status st-gateway sent, relayed with its own message and its Retry-After / X-RateLimit-* headers - 429 when the shared rate budget is spent, 401 when the injected agent token was rejected",
                     content = @Content),
        @ApiResponse(responseCode = "503", description = "auth-service could not verify a presented token (`the authentication service could not process this request`), or relayed from st-gateway: no SpaceTraders credential configured",
                     content = @Content),
        @ApiResponse(responseCode = "504", description = "st-gateway did not answer",
                     content = @Content),
        @ApiResponse(responseCode = "502", description = "st-gateway answered with something unreadable",
                     content = @Content)
    })
    @RequireScope(Scopes.UNIVERSE_REFRESH)
    @PostMapping("/waypoints/{symbol}/refresh")
    public ResponseEntity<JsonNode> refreshWaypoint(
            @Parameter(description = "Waypoint symbol, e.g. X1-FQ86-B29")
            @PathVariable String symbol,
            @Parameter(hidden = true)
            @RequestAttribute(value = CallerAttributes.SESSION_ATTRIBUTE, required = false) Session session,
            @Parameter(hidden = true)
            @RequestAttribute(value = CallerAttributes.CALLER_AUTHORIZATION_ATTRIBUTE, required = false)
            String callerAuthorization) {

        return ResponseEntity.ok(
                waypointService.refreshWaypoint(symbol, session, callerAuthorization));
    }

    // ── System waypoints ──────────────────────────────────────────────────────

    @Operation(
        summary = "List waypoints by system",
        description = """
            Returns all waypoints for a system. Serves from local cache only once a complete \
            walk of the system has been cached; otherwise fetches every page from SpaceTraders. \
            Use `forceRefresh=true` to re-walk the system."""
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "List of waypoints"),
        @ApiResponse(responseCode = "400", description = "Malformed system symbol", content = @Content),
        @ApiResponse(responseCode = "401", description = "Not cached and caller is anonymous, or auth-service says a presented token is not active",
                     content = @Content),
        @ApiResponse(responseCode = "404", description = "System not found", content = @Content),
        @ApiResponse(responseCode = "4XX", description = "Any other status st-gateway sent, relayed with its own message and its Retry-After / X-RateLimit-* headers - 429 when the shared rate budget is spent, 401 when the injected agent token was rejected",
                     content = @Content),
        @ApiResponse(responseCode = "503", description = "auth-service could not verify a presented token (`the authentication service could not process this request`), or relayed from st-gateway: no SpaceTraders credential configured",
                     content = @Content),
        @ApiResponse(responseCode = "504", description = "st-gateway did not answer",
                     content = @Content),
        @ApiResponse(responseCode = "502", description = "st-gateway answered with something unreadable",
                     content = @Content)
    })
    @AllowPublic
    @GetMapping("/systems/{systemSymbol}/waypoints")
    public ResponseEntity<Map<String, Object>> getWaypointsBySystem(
            @Parameter(description = "System symbol, e.g. X1-FQ86")
            @PathVariable String systemSymbol,
            @Parameter(description = "Bypass cache and re-fetch all waypoints for the system")
            @RequestParam(defaultValue = "false") boolean forceRefresh,
            @Parameter(hidden = true)
            @RequestAttribute(value = CallerAttributes.SESSION_ATTRIBUTE, required = false) Session session,
            @Parameter(hidden = true)
            @RequestAttribute(value = CallerAttributes.CALLER_AUTHORIZATION_ATTRIBUTE, required = false)
            String callerAuthorization) {

        return ResponseEntity.ok(listBody(waypointService.getWaypointsBySystem(
                systemSymbol, session, forceRefresh, callerAuthorization)));
    }

    @Operation(
        summary = "Refresh all waypoints for a system",
        description = "Force-fetches all waypoints for a system from SpaceTraders and replaces the local cache."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Updated list of waypoints"),
        @ApiResponse(responseCode = "400", description = "Malformed system symbol", content = @Content),
        @ApiResponse(responseCode = "401", description = "No bearer token, or auth-service says the presented one is not active", content = @Content),
        @ApiResponse(responseCode = "403", description = "A verified session without the universe:refresh scope", content = @Content),
        @ApiResponse(responseCode = "4XX", description = "Any other status st-gateway sent, relayed with its own message and its Retry-After / X-RateLimit-* headers - 429 when the shared rate budget is spent, 401 when the injected agent token was rejected",
                     content = @Content),
        @ApiResponse(responseCode = "503", description = "auth-service could not verify a presented token (`the authentication service could not process this request`), or relayed from st-gateway: no SpaceTraders credential configured",
                     content = @Content),
        @ApiResponse(responseCode = "504", description = "st-gateway did not answer",
                     content = @Content),
        @ApiResponse(responseCode = "502", description = "st-gateway answered with something unreadable",
                     content = @Content)
    })
    @RequireScope(Scopes.UNIVERSE_REFRESH)
    @PostMapping("/systems/{systemSymbol}/waypoints/refresh")
    public ResponseEntity<Map<String, Object>> refreshWaypointsBySystem(
            @Parameter(description = "System symbol, e.g. X1-FQ86")
            @PathVariable String systemSymbol,
            @Parameter(hidden = true)
            @RequestAttribute(value = CallerAttributes.SESSION_ATTRIBUTE, required = false) Session session,
            @Parameter(hidden = true)
            @RequestAttribute(value = CallerAttributes.CALLER_AUTHORIZATION_ATTRIBUTE, required = false)
            String callerAuthorization) {

        return ResponseEntity.ok(listBody(waypointService.refreshWaypointsBySystem(
                systemSymbol, session, callerAuthorization)));
    }

    /** {@code total} is the number of waypoints in this response, not SpaceTraders' page count. */
    private static Map<String, Object> listBody(List<JsonNode> waypoints) {
        return Map.of("data", waypoints, "total", waypoints.size());
    }
}
