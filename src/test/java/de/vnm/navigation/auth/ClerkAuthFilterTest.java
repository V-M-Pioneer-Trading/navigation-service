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
        when(waypointService.getWaypoint(eq(SYMBOL), isNull(), anyBoolean(), isNull())).thenReturn(waypoint());

        mockMvc.perform(get(READ)).andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, null, false, null);
    }

    /**
     * The filter publishes two things about a verified caller: the {@link Session} the
     * service reasons about, and the raw header the client forwards to st-gateway. The
     * second is asserted byte-for-byte: the contract is relay, not re-encode. This test
     * alone would not catch a reconstruction, because it sends the canonical spelling a
     * reconstruction would reproduce; {@link #bearerSchemeIsCaseInsensitive()} is the one
     * that does.
     */
    @Test
    void read_withValidSession_handsTheSessionAndTheRawHeaderToTheController() throws Exception {
        String bearer = TestClerk.bearer("fleet:control");
        when(waypointService.getWaypoint(eq(SYMBOL), any(Session.class), anyBoolean(), any())).thenReturn(waypoint());

        mockMvc.perform(get(READ).header("Authorization", bearer))
               .andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, new Session(TestClerk.ACTOR, java.util.Set.of("fleet:control")), false, bearer);
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

    /**
     * A non-Bearer Authorization header reads as no token — on a GET that is anonymous.
     * Nothing is forwarded upstream either: only a header this filter actually verified is
     * republished for relay, so a stray {@code Basic} credential is never handed to
     * st-gateway on the caller's behalf.
     */
    @Test
    void read_withNonBearerAuthorization_isAnonymousAndForwardsNothing() throws Exception {
        when(waypointService.getWaypoint(eq(SYMBOL), isNull(), anyBoolean(), isNull())).thenReturn(waypoint());

        mockMvc.perform(get(READ).header("Authorization", "Basic dXNlcjpwYXNz"))
               .andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, null, false, null);
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
        String bearer = TestClerk.bearer();
        when(waypointService.refreshWaypoint(eq(SYMBOL), any(Session.class), any())).thenReturn(waypoint());

        mockMvc.perform(post(REFRESH).header("Authorization", bearer))
               .andExpect(status().isOk())
               .andExpect(jsonPath("$.symbol").value(SYMBOL));

        verify(waypointService).refreshWaypoint(SYMBOL, TestClerk.OPERATOR, bearer);
    }

    /**
     * Also the test that pins "forwarded byte for byte, never reconstructed": a filter
     * that rebuilt the header as {@code "Bearer " + token} would hand the service the
     * canonical spelling, not the lowercase one the caller sent.
     */
    @Test
    void bearerSchemeIsCaseInsensitive() throws Exception {
        when(waypointService.refreshWaypoint(eq(SYMBOL), any(Session.class), any())).thenReturn(waypoint());

        String lower = TestClerk.bearer().replaceFirst("^Bearer", "bearer");
        mockMvc.perform(post(REFRESH).header("Authorization", lower))
               .andExpect(status().isOk());

        verify(waypointService).refreshWaypoint(SYMBOL, TestClerk.OPERATOR, lower);
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
