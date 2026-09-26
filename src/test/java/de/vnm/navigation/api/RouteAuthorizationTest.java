package de.vnm.navigation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.auth.Session;
import de.vnm.navigation.exception.GlobalExceptionHandler;
import de.vnm.navigation.introspection.TestCenter;
import de.vnm.navigation.service.WaypointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * navigation-service's own declarations, through the real interceptor, the real HTTP client
 * and a real stub of auth-service ({@link TestCenter}), on the real routes: public reads
 * with an optional identity, and refreshes that need {@code universe:refresh}.
 *
 * <p>The fixture's 37 conditions are pinned separately, and generically, by
 * {@code IntrospectionConformanceTest}; this class pins what only this service decides —
 * which route declares what, and what reaches the controller.
 */
@WebMvcTest(WaypointController.class)
@Import(GlobalExceptionHandler.class)
class RouteAuthorizationTest {

    @DynamicPropertySource
    static void center(DynamicPropertyRegistry registry) {
        TestCenter.register(registry);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean WaypointService waypointService;

    private static final String SYMBOL = "X1-FQ86-B29";
    private static final String READ = "/api/navigation/v1/waypoints/" + SYMBOL;
    private static final String REFRESH = READ + "/refresh";

    @BeforeEach
    void resetCenter() {
        TestCenter.center().resetCalls();
    }

    // ── reads: @AllowPublic ─────────────────────────────────────────────────────────────

    @Test
    void read_withoutAnyHeader_isAVisitorAndTheCenterIsNotAsked() throws Exception {
        when(waypointService.getWaypoint(eq(SYMBOL), isNull(), anyBoolean(), isNull())).thenReturn(waypoint());

        mockMvc.perform(get(READ)).andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, null, false, null);
        assertThat(TestCenter.center().calls()).isZero();
    }

    /**
     * The interceptor publishes two things about a verified caller: the {@link Session} the
     * service reasons about — {@code kind} included — and the raw header the client forwards
     * to st-gateway.
     */
    @Test
    void read_withAVerifiedToken_handsTheSessionAndTheRawHeaderToTheController() throws Exception {
        when(waypointService.getWaypoint(eq(SYMBOL), any(Session.class), anyBoolean(), any())).thenReturn(waypoint());

        mockMvc.perform(get(READ).header("Authorization", TestCenter.OPERATOR_BEARER)).andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, TestCenter.OPERATOR, false, TestCenter.OPERATOR_BEARER);
        assertThat(TestCenter.center().calls()).isEqualTo(1);
    }

    @Test
    void read_withAMachineToken_handsTheCentersKindThrough() throws Exception {
        when(waypointService.getWaypoint(eq(SYMBOL), any(Session.class), anyBoolean(), any())).thenReturn(waypoint());

        mockMvc.perform(get(READ).header("Authorization", TestCenter.MACHINE_BEARER)).andExpect(status().isOk());

        verify(waypointService).getWaypoint(SYMBOL, TestCenter.MACHINE, false, TestCenter.MACHINE_BEARER);
    }

    /** A bad credential is never quietly downgraded to a visitor, not even on a public read. */
    @Test
    void read_withAnInactiveToken_is401NotAnonymous() throws Exception {
        expectRejection(mockMvc.perform(get(READ).header("Authorization", TestCenter.INACTIVE_BEARER)),
                401, "invalid or expired session");

        verifyNoInteractions(waypointService);
        assertThat(TestCenter.center().calls()).isEqualTo(1);
    }

    /** A token presented while auth-service is broken is a 503, never a silent visitor. */
    @Test
    void read_withAToken_whileTheCenterIsBroken_is503() throws Exception {
        expectRejection(mockMvc.perform(get(READ).header("Authorization", TestCenter.CENTER_FAILS_BEARER)),
                503, "the authentication service could not process this request");

        verifyNoInteractions(waypointService);
    }

    /**
     * Anything that is not exactly {@code Bearer <token>} is no credential at all: on a public
     * read that is a visitor, the center is not asked, and nothing is forwarded upstream —
     * only a header auth-service vouched for is ever relayed to st-gateway.
     */
    @Test
    void read_withSomethingThatIsNotABearerToken_isAVisitorAndForwardsNothing() throws Exception {
        when(waypointService.getWaypoint(eq(SYMBOL), isNull(), anyBoolean(), isNull())).thenReturn(waypoint());

        for (String notABearer : new String[] {"Basic dXNlcjpwYXNz", "Bearer ", "Bearer abc def"}) {
            mockMvc.perform(get(READ).header("Authorization", notABearer)).andExpect(status().isOk());
        }
        mockMvc.perform(get(READ).header("Authorization", TestCenter.OPERATOR_BEARER, TestCenter.MACHINE_BEARER))
               .andExpect(status().isOk());

        verify(waypointService, times(4)).getWaypoint(SYMBOL, null, false, null);
        assertThat(TestCenter.center().calls()).isZero();
    }

    // ── refreshes: @RequireScope(universe:refresh) ──────────────────────────────────────

    @Test
    void refresh_withoutAnyHeader_is401AndTheCenterIsNotAsked() throws Exception {
        expectRejection(mockMvc.perform(post(REFRESH)), 401, "a bearer token is required");

        verifyNoInteractions(waypointService);
        assertThat(TestCenter.center().calls()).isZero();
    }

    /**
     * The regression the scope exists for: before 2026-09-05 any non-blank string in a header
     * was enough to walk a system on the fleet's real credential.
     */
    @Test
    void refresh_withOnlyTheOldGameTokenHeader_is401() throws Exception {
        expectRejection(mockMvc.perform(post(REFRESH).header("X-SpaceTraders-Token", "anything-at-all")),
                401, "a bearer token is required");

        verifyNoInteractions(waypointService);
    }

    /** Two {@code Authorization} lines are no credential, never "the first one". */
    @Test
    void refresh_withTwoAuthorizationHeaders_is401AndTheCenterIsNotAsked() throws Exception {
        expectRejection(mockMvc.perform(post(REFRESH)
                        .header("Authorization", TestCenter.OPERATOR_BEARER, TestCenter.OPERATOR_BEARER)),
                401, "a bearer token is required");

        assertThat(TestCenter.center().calls()).isZero();
    }

    /**
     * Regression: the lines used to be joined the way a proxy folds them, so a valid line
     * plus an empty one became {@code "Bearer a, "}, which splits into the credential
     * {@code "a,"} and was sent to the center. More than one line is no credential, whatever
     * the lines hold.
     */
    @Test
    void refresh_withAnEmptySecondAuthorizationLine_is401AndTheCenterIsNotAsked() throws Exception {
        expectRejection(mockMvc.perform(post(REFRESH).header("Authorization", TestCenter.OPERATOR_BEARER, "")),
                401, "a bearer token is required");

        verifyNoInteractions(waypointService);
        assertThat(TestCenter.center().calls()).isZero();
    }

    /** {@code fleet:control} does not imply {@code universe:refresh} (decision 20), and the 403 names neither. */
    @Test
    void refresh_withASessionLackingTheScope_is403() throws Exception {
        String body = expectRejection(mockMvc.perform(post(REFRESH).header("Authorization", TestCenter.FLEET_CONTROL_BEARER)),
                403, "this action requires a scope this session does not carry");

        assertThat(body).doesNotContain("universe:refresh").doesNotContain("fleet:control");
        verifyNoInteractions(waypointService);
    }

    @Test
    void refresh_withASessionHoldingNoScopeAtAll_is403() throws Exception {
        expectRejection(mockMvc.perform(post(REFRESH).header("Authorization", TestCenter.SCOPELESS_BEARER)),
                403, "this action requires a scope this session does not carry");
    }

    @Test
    void refresh_withAnInactiveToken_is401() throws Exception {
        expectRejection(mockMvc.perform(post(REFRESH).header("Authorization", TestCenter.INACTIVE_BEARER)),
                401, "invalid or expired session");
    }

    @Test
    void refresh_whileTheCenterIsBroken_is503NotRelayed() throws Exception {
        String body = expectRejection(mockMvc.perform(post(REFRESH).header("Authorization", TestCenter.CENTER_FAILS_BEARER)),
                503, "the authentication service could not process this request");

        assertThat(body).doesNotContain("sql").doesNotContain("database");
    }

    @Test
    void refresh_withTheScope_reachesTheController() throws Exception {
        when(waypointService.refreshWaypoint(eq(SYMBOL), any(Session.class), any())).thenReturn(waypoint());

        mockMvc.perform(post(REFRESH).header("Authorization", TestCenter.OPERATOR_BEARER))
               .andExpect(status().isOk());

        verify(waypointService).refreshWaypoint(SYMBOL, TestCenter.OPERATOR, TestCenter.OPERATOR_BEARER);
        assertThat(TestCenter.center().calls()).isEqualTo(1);
    }

    /**
     * Also the test that pins "forwarded byte for byte, never reconstructed": an interceptor
     * that rebuilt the header as {@code "Bearer " + token} would hand the service the
     * canonical spelling, not the one the caller sent.
     */
    @Test
    void theBearerSchemeIsCaseInsensitive_andTheHeaderIsForwardedAsSent() throws Exception {
        when(waypointService.refreshWaypoint(eq(SYMBOL), any(Session.class), any())).thenReturn(waypoint());

        String asSent = "bearer   " + TestCenter.OPERATOR_BEARER.substring("Bearer ".length());
        mockMvc.perform(post(REFRESH).header("Authorization", asSent)).andExpect(status().isOk());

        verify(waypointService).refreshWaypoint(SYMBOL, TestCenter.OPERATOR, asSent);
    }

    /**
     * The envelope is written by the interceptor itself, so neither {@code GlobalExceptionHandler}
     * (RFC 9457) nor Spring's error page can re-render it: the body is exactly these bytes.
     */
    private String expectRejection(ResultActions result, int status, String message) throws Exception {
        String expected = "{\"error\":{\"message\":\"" + message + "\"}}";
        result.andExpect(status().is(status))
              .andExpect(header().string("Content-Type", "application/json"))
              .andExpect(content().string(expected));
        return result.andReturn().getResponse().getContentAsString();
    }

    private JsonNode waypoint() throws Exception {
        return objectMapper.readTree("""
                {"symbol":"X1-FQ86-B29","type":"ASTEROID","x":10,"y":20}""");
    }
}
