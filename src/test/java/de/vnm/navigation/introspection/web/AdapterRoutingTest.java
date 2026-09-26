package de.vnm.navigation.introspection.web;

import de.vnm.navigation.api.HealthController;
import de.vnm.navigation.api.WaypointController;
import de.vnm.navigation.exception.GlobalExceptionHandler;
import de.vnm.navigation.introspection.TestCenter;
import de.vnm.navigation.service.WaypointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the fixture cannot reach: how Spring MVC's own routing binds a declaration to a
 * request. Real {@code DispatcherServlet} routing, the real interceptor, the real client and
 * the shared stub center.
 *
 * <ul>
 *   <li>{@code HEAD} is dispatched by Spring to the {@code GET} handler, so it carries that
 *       handler's declaration — exempt from default-deny, and from nothing else.</li>
 *   <li>An {@code OPTIONS} no handler maps is answered by Spring's own responder, declared
 *       {@code none}; a handler that maps {@code OPTIONS} itself is governed by its own
 *       declaration; a CORS preflight is terminated by the CORS layer.</li>
 *   <li>A trailing slash or a case-variant path matches no handler at all in Spring 6, so it
 *       is a 404 — never a handler served without its declaration.</li>
 *   <li>A parameterised path carries its handler's declaration whatever the parameter.</li>
 * </ul>
 */
@WebMvcTest(controllers = {WaypointController.class, HealthController.class})
@Import({GlobalExceptionHandler.class, AdapterRoutingTest.Probe.class})
class AdapterRoutingTest {

    @DynamicPropertySource
    static void center(DynamicPropertyRegistry registry) {
        TestCenter.register(registry);
    }

    /** Routes this service does not have, but the adapter must handle correctly if it ever does. */
    @RestController
    @RequestMapping("/probe")
    static class Probe {

        static final AtomicInteger RAN = new AtomicInteger();

        @RequireScope("fleet:control")
        @GetMapping("/scoped-read")
        String scopedRead() {
            RAN.incrementAndGet();
            return "ran";
        }

        /** The {@code app.all} shape: the real handler answers OPTIONS too. */
        @RequireScope("fleet:control")
        @RequestMapping(value = "/scoped-write", method = {RequestMethod.POST, RequestMethod.OPTIONS})
        String scopedWrite() {
            RAN.incrementAndGet();
            return "ran";
        }
    }

    @Autowired MockMvc mockMvc;

    @MockitoBean WaypointService waypointService;

    private static final String READ = "/api/navigation/v1/waypoints/X1-FQ86-B29";
    private static final String REFRESH = READ + "/refresh";

    @BeforeEach
    void reset() {
        TestCenter.center().resetCalls();
        Probe.RAN.set(0);
    }

    // ── HEAD ───────────────────────────────────────────────────────────────────────────

    @Test
    void headOfAPublicRead_isAVisitor_andTheCenterIsNotAsked() throws Exception {
        mockMvc.perform(head(READ)).andExpect(status().isOk());

        verify(waypointService).getWaypoint("X1-FQ86-B29", null, false, null);
        assertThat(TestCenter.center().calls()).isZero();
    }

    /** HEAD is the same route as GET: a bad credential is the same 401 there. */
    @Test
    void headOfAPublicRead_withAnInactiveToken_is401() throws Exception {
        mockMvc.perform(head(READ).header("Authorization", TestCenter.INACTIVE_BEARER))
               .andExpect(status().isUnauthorized());

        verifyNoInteractions(waypointService);
    }

    @Test
    void headOfAScopedRead_withoutAHeader_is401AndTheHandlerDoesNotRun() throws Exception {
        mockMvc.perform(head("/probe/scoped-read"))
               .andExpect(status().isUnauthorized())
               .andExpect(header().string("Content-Type", "application/json"));

        assertThat(Probe.RAN).hasValue(0);
        assertThat(TestCenter.center().calls()).isZero();
    }

    @Test
    void headOfAScopedRead_withTheScope_proceeds() throws Exception {
        mockMvc.perform(head("/probe/scoped-read").header("Authorization", TestCenter.FLEET_CONTROL_BEARER))
               .andExpect(status().isOk());

        assertThat(Probe.RAN).hasValue(1);
        assertThat(TestCenter.center().calls()).isEqualTo(1);
    }

    // ── OPTIONS ────────────────────────────────────────────────────────────────────────

    /**
     * No handler maps OPTIONS on a refresh route, so Spring answers it with {@code Allow}
     * itself — declared {@code none}: a visitor proceeds, no handler runs, and the center is
     * not asked. The route's existence and methods are disclosed, as Express discloses them.
     */
    @Test
    void optionsOnARouteThatDoesNotMapIt_isAnsweredBySpring() throws Exception {
        mockMvc.perform(options(REFRESH))
               .andExpect(status().isOk())
               .andExpect(header().string("Allow", containsString("POST")));

        verifyNoInteractions(waypointService);
        assertThat(TestCenter.center().calls()).isZero();
    }

    /** {@code none} still verifies what it is shown: a bad credential is never a visitor. */
    @Test
    void optionsOnARouteThatDoesNotMapIt_withAnInactiveToken_is401() throws Exception {
        mockMvc.perform(options(REFRESH).header("Authorization", TestCenter.INACTIVE_BEARER))
               .andExpect(status().isUnauthorized());

        assertThat(TestCenter.center().calls()).isEqualTo(1);
    }

    /** The fixture's {@code options-on-guarded-route-with-no-header}, on a real route. */
    @Test
    void optionsOnAHandlerThatMapsIt_isGovernedByItsDeclaration() throws Exception {
        mockMvc.perform(options("/probe/scoped-write"))
               .andExpect(status().isUnauthorized())
               .andExpect(content().string("{\"error\":{\"message\":\"a bearer token is required\"}}"));

        assertThat(Probe.RAN).hasValue(0);
    }

    /**
     * A preflight carries no {@code Authorization} header by definition. It works because the
     * CORS layer terminates it — not because a guarded route waves OPTIONS through, which the
     * test above rules out.
     */
    @Test
    void aCorsPreflight_isTerminatedByTheCorsLayer() throws Exception {
        mockMvc.perform(options(REFRESH)
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization"))
               .andExpect(status().isOk())
               .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));

        verifyNoInteractions(waypointService);
        assertThat(TestCenter.center().calls()).isZero();
    }

    // ── paths ──────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            REFRESH + "/",
            "/API/navigation/v1/waypoints/X1-FQ86-B29/refresh",
            "/api/navigation/v1/WAYPOINTS/X1-FQ86-B29/refresh",
            "/api/navigation/v1/waypoints/X1-FQ86-B29/Refresh",
            "/api/navigation/v1/nothing-here",
    })
    void aPathNoHandlerMatches_isA404_neverAnUndeclaredServe(String path) throws Exception {
        mockMvc.perform(post(path).header("Authorization", TestCenter.OPERATOR_BEARER))
               .andExpect(status().isNotFound());

        verifyNoInteractions(waypointService);
        assertThat(TestCenter.center().calls()).isZero();
    }

    @Test
    void aTrailingSlashOnARead_isA404() throws Exception {
        mockMvc.perform(get(READ + "/")).andExpect(status().isNotFound());

        verifyNoInteractions(waypointService);
    }

    @Test
    void aMethodTheRouteDoesNotMap_isA405_notA500() throws Exception {
        mockMvc.perform(put(REFRESH).header("Authorization", TestCenter.OPERATOR_BEARER))
               .andExpect(status().isMethodNotAllowed());

        assertThat(TestCenter.center().calls()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/navigation/v1/waypoints/X1-FQ86-B29/refresh",
            "/api/navigation/v1/waypoints/X9-ZZ99-A1/refresh",
            "/api/navigation/v1/systems/X1-FQ86/waypoints/refresh",
    })
    void aParameterisedRefresh_carriesItsDeclarationWhateverTheParameter(String path) throws Exception {
        mockMvc.perform(post(path))
               .andExpect(status().isUnauthorized())
               .andExpect(content().string("{\"error\":{\"message\":\"a bearer token is required\"}}"));
        mockMvc.perform(post(path).header("Authorization", TestCenter.FLEET_CONTROL_BEARER))
               .andExpect(status().isForbidden());

        verifyNoInteractions(waypointService);
    }

    @Test
    void aParameterisedRefresh_withTheScope_reachesItsOwnHandler() throws Exception {
        when(waypointService.refreshWaypointsBySystem(any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(post("/api/navigation/v1/systems/X1-FQ86/waypoints/refresh")
                        .header("Authorization", TestCenter.OPERATOR_BEARER))
               .andExpect(status().isOk());

        verify(waypointService).refreshWaypointsBySystem("X1-FQ86", TestCenter.OPERATOR, TestCenter.OPERATOR_BEARER);
    }

    // ── health ─────────────────────────────────────────────────────────────────────────

    @Test
    void healthWithAGarbageBearer_neverTouchesTheCenter() throws Exception {
        mockMvc.perform(get("/health").header("Authorization", "Bearer garbage"))
               .andExpect(status().isOk());
        mockMvc.perform(get("/api/navigation/health").header("Authorization", "Bearer garbage"))
               .andExpect(status().isOk());

        assertThat(TestCenter.center().calls()).isZero();
    }

    /** {@code @IgnoreCredentials} answers safe methods only. */
    @Test
    void aMutationOnHealth_isNotServed() throws Exception {
        mockMvc.perform(post("/health")).andExpect(status().isMethodNotAllowed());
    }
}
