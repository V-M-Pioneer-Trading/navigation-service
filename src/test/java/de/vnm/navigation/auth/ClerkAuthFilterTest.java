package de.vnm.navigation.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.api.WaypointController;
import de.vnm.navigation.config.ClerkConfig;
import de.vnm.navigation.exception.GlobalExceptionHandler;
import de.vnm.navigation.service.WaypointService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authorization rule through the real filter and the real verifier, on real routes.
 * No stub verifier, no bypass flag: the trust anchor is the only thing swapped
 * (auth-design.md decision 10).
 */
@WebMvcTest(WaypointController.class)
@Import({GlobalExceptionHandler.class, ClerkConfig.class})
class ClerkAuthFilterTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean WaypointService waypointService;

    @DynamicPropertySource
    static void trustAnchor(DynamicPropertyRegistry registry) {
        registry.add("clerk.jwt-key", TestClerk::publicKeyPem);
    }

    private static final String SYMBOL = "X1-FQ86-B29";
    private static final String READ = "/api/navigation/v1/waypoints/" + SYMBOL;
    private static final String REFRESH = READ + "/refresh";

    // ── reads: public, session optional ────────────────────────────────────────────────

    @Test
    void read_withoutAnyHeader_isAnonymous() throws Exception {
        when(waypointService.getWaypoint(eq(SYMBOL), isNull(), anyBoolean())).thenReturn(waypoint());

        mockMvc.perform(get(READ)).andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, null, false);
    }

    @Test
    void read_withValidSession_handsTheSessionToTheController() throws Exception {
        when(waypointService.getWaypoint(eq(SYMBOL), any(Session.class), anyBoolean())).thenReturn(waypoint());

        mockMvc.perform(get(READ).header("Authorization", TestClerk.bearer("fleet:control")))
               .andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, new Session(TestClerk.ACTOR, java.util.Set.of("fleet:control")), false);
    }

    /** A bad credential is never quietly downgraded to anonymous. */
    @Test
    void read_withInvalidToken_is401NotAnonymous() throws Exception {
        mockMvc.perform(get(READ).header("Authorization", TestClerk.foreignBearer()))
               .andExpect(status().isUnauthorized())
               .andExpect(content().contentType(MediaType.APPLICATION_JSON))
               .andExpect(jsonPath("$.error.message").value(ClerkAuthFilter.INVALID_SESSION));

        verifyNoInteractions(waypointService);
    }

    @Test
    void read_withExpiredToken_is401() throws Exception {
        mockMvc.perform(get(READ).header("Authorization", TestClerk.expiredBearer()))
               .andExpect(status().isUnauthorized())
               .andExpect(jsonPath("$.error.message").value(ClerkAuthFilter.INVALID_SESSION));
    }

    /** A non-Bearer Authorization header reads as no token — on a GET that is anonymous. */
    @Test
    void read_withNonBearerAuthorization_isAnonymous() throws Exception {
        when(waypointService.getWaypoint(eq(SYMBOL), isNull(), anyBoolean())).thenReturn(waypoint());

        mockMvc.perform(get(READ).header("Authorization", "Basic dXNlcjpwYXNz"))
               .andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, null, false);
    }

    // ── refresh: universe:refresh required ─────────────────────────────────────────────

    @Test
    void refresh_withoutAnyHeader_is401() throws Exception {
        mockMvc.perform(post(REFRESH))
               .andExpect(status().isUnauthorized())
               .andExpect(content().contentType(MediaType.APPLICATION_JSON))
               .andExpect(jsonPath("$.error.message").value(ClerkAuthFilter.MISSING_TOKEN));

        verifyNoInteractions(waypointService);
    }

    /**
     * The regression this filter exists for: before it, any non-blank string in a header was
     * enough to walk a system on the fleet's real credential, since st-gateway injects the
     * agent token on every call regardless of what the caller presented.
     */
    @Test
    void refresh_withOnlyTheOldGameTokenHeader_is401() throws Exception {
        mockMvc.perform(post(REFRESH).header("X-SpaceTraders-Token", "anything-at-all"))
               .andExpect(status().isUnauthorized());

        verifyNoInteractions(waypointService);
    }

    @Test
    void refresh_withSessionLackingTheScope_is403() throws Exception {
        mockMvc.perform(post(REFRESH).header("Authorization", TestClerk.bearer("fleet:control")))
               .andExpect(status().isForbidden())
               .andExpect(jsonPath("$.error.message").value(ClerkAuthFilter.MISSING_SCOPE));

        verifyNoInteractions(waypointService);
    }

    /** {@code fleet:control} does not imply {@code universe:refresh}; the literal is required. */
    @Test
    void refresh_withNoScopeAtAll_is403() throws Exception {
        mockMvc.perform(post(REFRESH).header("Authorization", TestClerk.bearerWithoutScope()))
               .andExpect(status().isForbidden());
    }

    @Test
    void refresh_withForeignSignature_is401() throws Exception {
        mockMvc.perform(post(REFRESH).header("Authorization", TestClerk.foreignBearer()))
               .andExpect(status().isUnauthorized())
               .andExpect(jsonPath("$.error.message").value(ClerkAuthFilter.INVALID_SESSION));
    }

    @Test
    void refresh_withTheScope_reachesTheController() throws Exception {
        when(waypointService.refreshWaypoint(eq(SYMBOL), any(Session.class))).thenReturn(waypoint());

        mockMvc.perform(post(REFRESH).header("Authorization", TestClerk.bearer()))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.symbol").value(SYMBOL));

        verify(waypointService).refreshWaypoint(SYMBOL, TestClerk.OPERATOR);
    }

    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        when(waypointService.refreshWaypoint(eq(SYMBOL), any(Session.class))).thenReturn(waypoint());

        String lower = TestClerk.bearer().replaceFirst("^Bearer", "bearer");
        mockMvc.perform(post(REFRESH).header("Authorization", lower))
               .andExpect(status().isOk());
    }

    // ── outside the guarded prefix ─────────────────────────────────────────────────────

    @Test
    void healthIsNotGuarded() throws Exception {
        // HealthController is not in this slice; a 404 (not a 401) shows the filter stood aside.
        mockMvc.perform(get("/health")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/navigation/health")).andExpect(status().isNotFound());
    }

    private JsonNode waypoint() throws Exception {
        return objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","x":10,"y":20}""");
    }
}
