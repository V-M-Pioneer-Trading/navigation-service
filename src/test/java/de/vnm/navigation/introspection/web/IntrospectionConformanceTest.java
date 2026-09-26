package de.vnm.navigation.introspection.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.auth.CallerAttributes;
import de.vnm.navigation.auth.Session;
import de.vnm.navigation.introspection.AccessPolicy;
import de.vnm.navigation.introspection.IntrospectionClient;
import de.vnm.navigation.introspection.IntrospectionSettings;
import de.vnm.navigation.introspection.Rejection;
import de.vnm.navigation.introspection.StubCenter;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ExtendWith;
import org.opentest4j.TestAbortedException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Conformance against {@code meta/fixtures/introspection.json}, vendored verbatim into
 * {@code src/test/resources/introspection/} (provenance and sha256 in {@code SOURCE.txt}).
 *
 * <p>Every one of the 37 calling-service cases is driven through the real
 * {@link IntrospectionInterceptor} and the real {@link IntrospectionClient} against a real
 * HTTP stub of the center implementing the case's {@code center} object: {@code status},
 * {@code body}, {@code delayMs}, {@code notCalled}, and {@code transport: "no-response"} (a
 * listener that accepts and hangs up, so the call is still counted). Every {@code expect}
 * key is asserted, and an unknown key anywhere in a case fails that case rather than being
 * skipped, so a copy that falls behind meta says so.
 *
 * <p>The fixture hands {@code route.requires} over already resolved, so each case is given
 * the {@link HandlerMethod} of a probe whose annotation resolves to exactly that
 * requirement — through {@link Declarations}, the same resolver production uses — and the
 * interceptor is called the way {@code DispatcherServlet} calls it, with the method the
 * request will be handled as ({@code route.method}, lower-case {@code post} included).
 * How Spring binds a declaration to a real route is the adapter's own obligation, tested in
 * {@code AdapterRoutingTest}.
 *
 * <p>The 11 st-gateway cases are a different policy (a lane, never a verdict) that this
 * service does not implement. They are skipped by name, and their count is asserted, so a
 * gateway case added in meta is noticed here too.
 */
@ExtendWith(OutputCaptureExtension.class)
class IntrospectionConformanceTest {

    private static final String FIXTURE = "/introspection/introspection.json";
    private static final String SOURCE = "/introspection/SOURCE.txt";
    private static final String PINNED_SHA256 = "77f845c89d4baabef7a336325a9e30360d908fd761ad450904c693621547dfa3";

    /** Stands in for {@code <AUTH_INTROSPECTION_SECRET>} in the fixture. */
    private static final String SECRET = "conformance-caller-secret-7f3a9c";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One handler per requirement the fixture uses; the annotation is the declaration under test. */
    static final class FixtureRoutes {
        @AllowPublic public void none() {}
        @RequireSession public void session() {}
        @RequireScope("fleet:control") public void fleetControl() {}
        @RequireScope("agent:reset") public void agentReset() {}
        @RequireScope("universe:refresh") public void universeRefresh() {}
    }

    // ── the copy itself ────────────────────────────────────────────────────────────────

    @Test
    void theVendoredFixtureIsTheExactCopySourceTxtClaims() throws Exception {
        byte[] raw = resource(FIXTURE);
        String source = new String(resource(SOURCE), StandardCharsets.UTF_8);

        Matcher recorded = Pattern.compile("(?m)^\\s*sha256:\\s*([0-9a-f]{64})\\s*$").matcher(source);
        assertThat(recorded.find()).as("SOURCE.txt records a sha256").isTrue();
        assertThat(recorded.group(1))
                .as("SOURCE.txt; this test was written against meta 9b62746 (fixture version 3)")
                .isEqualTo(PINNED_SHA256);

        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        if (!actual.equals(PINNED_SHA256)) {
            boolean crlf = new String(raw, StandardCharsets.UTF_8).contains("\r\n");
            fail(crlf
                    ? "introspection.json has CRLF line endings: the -text entry in .gitattributes is missing,"
                      + " or the file was checked out before it was added"
                    : "introspection.json hashes to " + actual + ", SOURCE.txt records " + PINNED_SHA256
                      + " — re-copy it from meta and update BOTH the commit and the sha256 in SOURCE.txt");
        }
        assertThat(raw).hasSize(49347);
        assertThat(fixture().path("version").asInt()).as("fixture version").isEqualTo(3);
    }

    @Test
    void theCaseNamesAreTheOnesThisTestWasWrittenAgainst() throws Exception {
        Set<String> names = new TreeSet<>();
        fixture().path("cases").forEach(c -> names.add(c.path("name").asText()));
        fixture().path("gatewayCases").forEach(c -> names.add(c.path("name").asText()));
        assertThat(names).containsExactly(
                "active-machine-kind", "active-with-irregular-scope-whitespace", "active-with-multi-value-scope",
                "active-with-required-scope", "active-with-scope-differing-only-in-case",
                "active-with-scope-that-is-a-prefix-of-required", "active-without-required-scope",
                "bearer-with-empty-token", "bearer-with-internal-whitespace", "center-rejects-our-caller-secret",
                "center-returns-500", "center-returns-malformed-json", "center-times-out", "center-unreachable",
                "gateway-active-machine", "gateway-active-operator", "gateway-active-operator-lacking-scope-key",
                "gateway-bearer-with-empty-token", "gateway-center-rejects-our-caller-secret",
                "gateway-center-unreachable", "gateway-inactive-token", "gateway-kind-machine-with-user-subject",
                "gateway-kind-operator-with-machine-subject", "gateway-no-header", "gateway-non-bearer-scheme",
                "head-on-guarded-route-with-no-header", "head-on-guarded-route-with-valid-token", "head-on-public-get",
                "inactive-token-on-guarded-route", "inactive-token-on-public-get", "kind-disagrees-with-sub-prefix",
                "lowercase-bearer-scheme", "lowercase-route-method", "mutating-route-with-no-declared-scope",
                "mutating-route-with-no-declared-scope-and-inactive-token",
                "mutating-route-with-no-declared-scope-and-no-header", "no-header-on-guarded-route",
                "non-bearer-scheme-on-guarded-route", "operator-on-public-get",
                "options-on-guarded-route-with-no-header", "options-with-no-declared-scope",
                "scoped-route-with-token-lacking-scope-key", "session-route-with-inactive-token",
                "session-route-with-no-header", "session-route-with-scopeless-token",
                "session-route-with-token-lacking-scope-key", "token-on-public-get-while-center-is-down",
                "visitor-on-public-get");
    }

    /** Every name and number three implementations agree on. */
    @Test
    void theContractMatchesTheCode() throws Exception {
        JsonNode contract = fixture().path("contract");
        assertThat(contract.at("/endpoint/method").asText()).isEqualTo("POST");
        assertThat(contract.at("/endpoint/path").asText()).isEqualTo(StubCenter.PATH);
        assertThat(contract.at("/endpoint/contentType").asText()).isEqualTo("application/x-www-form-urlencoded");
        assertThat(contract.at("/endpoint/bodyTemplate").asText()).isEqualTo("token=<jwt>");
        assertThat(contract.at("/endpoint/secretHeader").asText()).isEqualTo(IntrospectionSettings.SECRET_HEADER);
        assertThat(contract.at("/env/url").asText()).isEqualTo(IntrospectionSettings.ENV_URL);
        assertThat(contract.at("/env/secret").asText()).isEqualTo(IntrospectionSettings.ENV_SECRET);
        assertThat(contract.path("clientTimeoutMs").asLong()).isEqualTo(IntrospectionClient.TIMEOUT.toMillis());
        assertThat(contract.path("retries").asInt()).isZero();
        assertThat(contract.path("errorEnvelope").asText()).isEqualTo("{\"error\":{\"message\":\"…\"}}");

        JsonNode messages = contract.path("messages");
        assertThat(messages.size()).as("messages this test knows").isEqualTo(5);
        assertThat(messages.path("missingToken").asText()).isEqualTo(Rejection.MISSING_TOKEN.message());
        assertThat(messages.path("invalidSession").asText()).isEqualTo(Rejection.INVALID_SESSION.message());
        assertThat(messages.path("missingScope").asText()).isEqualTo(Rejection.MISSING_SCOPE.message());
        assertThat(messages.path("undeclaredRoute").asText()).isEqualTo(Rejection.UNDECLARED_ROUTE.message());
        assertThat(messages.path("centerUnavailable").asText()).isEqualTo(Rejection.CENTER_UNAVAILABLE.message());
    }

    // ── the 37 calling-service cases ───────────────────────────────────────────────────

    @TestFactory
    Stream<DynamicTest> callingServiceCases(CapturedOutput output) throws Exception {
        JsonNode cases = fixture().path("cases");
        assertThat(cases.size()).as("calling-service cases; this test was written against 37").isEqualTo(37);
        String endpointPath = fixture().at("/contract/endpoint/path").asText();

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases) {
            tests.add(DynamicTest.dynamicTest(c.path("name").asText(), () -> runCase(c, endpointPath, output)));
        }
        return tests.stream();
    }

    /**
     * st-gateway's lane policy is not this service's. Skipped by name; the count is the
     * assertion, so someone decides whether a newly added gateway case is really gateway-only.
     */
    @TestFactory
    Stream<DynamicTest> gatewayCasesAreNotThisServicesPolicy() throws Exception {
        JsonNode gatewayCases = fixture().path("gatewayCases");
        assertThat(gatewayCases.size()).as("gateway cases; this test was written against 11").isEqualTo(11);

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : gatewayCases) {
            tests.add(DynamicTest.dynamicTest(c.path("name").asText(), () -> {
                throw new TestAbortedException(
                        "st-gateway lane policy (a lane, never a verdict); navigation-service is a calling service");
            }));
        }
        return tests.stream();
    }

    private void runCase(JsonNode c, String endpointPath, CapturedOutput output) throws Exception {
        allowOnly("case", c, "name", "why", "route", "request", "center", "expect");
        allowOnly("route", c.path("route"), "method", "requires");
        allowOnly("request", c.path("request"), "authorization");
        allowOnly("center", c.path("center"), "notCalled", "status", "body", "delayMs", "transport");
        allowOnly("expect", c.path("expect"), "outcome", "identity", "centerCalls", "centerRequest",
                "status", "message", "messageMustNotContain", "maxElapsedMs");

        String method = required(c, "/route/method").asText();
        String requires = required(c, "/route/requires").asText();
        JsonNode authorizationNode = required(c, "/request/authorization");
        String authorization = authorizationNode.isNull() ? null : authorizationNode.asText();
        JsonNode expect = c.path("expect");
        int logStart = output.getAll().length();

        try (StubCenter center = stage(c.path("center"));
             IntrospectionClient client = new IntrospectionClient(IntrospectionSettings.of(center.url(), SECRET))) {

            IntrospectionInterceptor interceptor = new IntrospectionInterceptor(new AccessPolicy(client));
            MockHttpServletRequest request = new MockHttpServletRequest(method, "/route-under-test");
            if (authorization != null) {
                request.addHeader("Authorization", authorization);
            }
            MockHttpServletResponse response = new MockHttpServletResponse();

            long started = System.nanoTime();
            boolean proceeded = interceptor.preHandle(request, response, handlerDeclaring(requires));
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            switch (required(c, "/expect/outcome").asText()) {
                case "proceed" -> assertProceeded(expect, proceeded, request, response, authorization);
                case "reject" -> assertRejected(expect, proceeded, request, response);
                default -> fail("unknown outcome " + expect.path("outcome"));
            }

            assertThat(center.calls()).as("center calls").isEqualTo(required(c, "/expect/centerCalls").asInt());
            if (expect.has("maxElapsedMs")) {
                assertThat(elapsedMs).as("elapsed ms").isLessThanOrEqualTo(expect.path("maxElapsedMs").asLong());
            }
            for (StubCenter.Received sent : center.received()) {
                assertSentRequest(sent, authorization, endpointPath);
            }
            if (expect.has("centerRequest")) {
                assertCenterRequest(expect.path("centerRequest"), center.received());
            }

            // Nothing sensitive reaches a log line or a response body, in any case.
            String logs = output.getAll().substring(logStart);
            String body = response.getContentAsString();
            assertThat(logs).as("log").doesNotContain(SECRET);
            assertThat(body).as("response body").doesNotContain(SECRET);
            String token = tokenOf(authorization);
            if (token != null && !token.isEmpty()) {
                assertThat(logs).as("log").doesNotContain(token);
                assertThat(body).as("response body").doesNotContain(token);
            }
        }
    }

    private static void assertProceeded(JsonNode expect, boolean proceeded, MockHttpServletRequest request,
                                        MockHttpServletResponse response, String authorization) throws Exception {
        for (String key : List.of("status", "message", "messageMustNotContain")) {
            assertThat(expect.has(key)).as("a proceed case asserting %s; this test does not know what that means", key)
                    .isFalse();
        }
        assertThat(proceeded).as("handler ran; answered %d %s", response.getStatus(), response.getContentAsString())
                .isTrue();
        assertThat(response.getContentAsString()).as("nothing written before the handler").isEmpty();
        assertThat(response.getStatus()).isEqualTo(200);

        assertThat(expect.has("identity")).as("a proceed case must assert the identity").isTrue();
        JsonNode identity = expect.path("identity");
        Object session = request.getAttribute(CallerAttributes.SESSION_ATTRIBUTE);
        Object forwarded = request.getAttribute(CallerAttributes.CALLER_AUTHORIZATION_ATTRIBUTE);
        if (identity.isNull()) {
            assertThat(session).as("a visitor has no session").isNull();
            assertThat(forwarded).as("a visitor forwards nothing").isNull();
            return;
        }
        allowOnly("expect.identity", identity, "sub", "kind", "scopes");
        List<String> scopes = new ArrayList<>();
        required(identity, "/scopes").forEach(s -> scopes.add(s.asText()));
        assertThat(session).isEqualTo(new Session(
                required(identity, "/sub").asText(), required(identity, "/kind").asText(), scopes));
        assertThat(forwarded).as("the raw header, republished for st-gateway").isEqualTo(authorization);
    }

    private static void assertRejected(JsonNode expect, boolean proceeded, MockHttpServletRequest request,
                                       MockHttpServletResponse response) throws Exception {
        assertThat(expect.has("identity")).as("a reject case asserting an identity").isFalse();
        assertThat(proceeded).as("handler ran on a rejected request").isFalse();
        assertThat(request.getAttribute(CallerAttributes.SESSION_ATTRIBUTE)).isNull();
        assertThat(request.getAttribute(CallerAttributes.CALLER_AUTHORIZATION_ATTRIBUTE)).isNull();

        assertThat(response.getStatus()).isEqualTo(required(expect, "/status").asInt());
        assertThat(response.getContentType()).isEqualTo("application/json");
        String body = response.getContentAsString();
        JsonNode envelope = JSON.readTree(body);
        allowOnly("the envelope", envelope, "error");
        allowOnly("the envelope's error", envelope.path("error"), "message");
        assertThat(envelope.at("/error/message").isTextual()).as("error.message in %s", body).isTrue();
        assertThat(envelope.at("/error/message").asText()).isEqualTo(required(expect, "/message").asText());

        if (expect.has("messageMustNotContain")) {
            JsonNode forbidden = expect.path("messageMustNotContain");
            assertThat(forbidden.size()).as("messageMustNotContain is not empty").isPositive();
            forbidden.forEach(f -> assertThat(body).doesNotContain(f.asText()));
        }
    }

    /** What was sent, for every call that reached the center. */
    private static void assertSentRequest(StubCenter.Received sent, String authorization, String endpointPath) {
        String token = tokenOf(authorization);
        assertThat(token).as("the center was called for a request carrying no bearer token").isNotNull();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo(endpointPath);
        assertThat(sent.rawQuery()).as("query string").isNull();
        assertThat(sent.header("Content-Type")).isEqualTo("application/x-www-form-urlencoded");
        assertThat(sent.header(IntrospectionSettings.SECRET_HEADER)).isEqualTo(SECRET);
        assertThat(sent.body()).isEqualTo("token=" + URLEncoder.encode(token, StandardCharsets.UTF_8));
        assertThat(sent.requestUri()).as("the token is never in a URL").doesNotContain(token);
    }

    private static void assertCenterRequest(JsonNode want, List<StubCenter.Received> received) {
        allowOnly("expect.centerRequest", want, "method", "path", "contentType", "body", "headers");
        assertThat(received).hasSize(1);
        StubCenter.Received sent = received.get(0);
        assertThat(sent.method()).isEqualTo(required(want, "/method").asText());
        assertThat(sent.path()).isEqualTo(required(want, "/path").asText());
        assertThat(sent.header("Content-Type")).isEqualTo(required(want, "/contentType").asText());
        assertThat(sent.body()).isEqualTo(required(want, "/body").asText());
        JsonNode headers = required(want, "/headers");
        assertThat(headers.size()).as("centerRequest.headers is not empty").isPositive();
        headers.fields().forEachRemaining(h -> {
            String value = h.getValue().asText();
            assertThat(sent.header(h.getKey())).isEqualTo("<AUTH_INTROSPECTION_SECRET>".equals(value) ? SECRET : value);
        });
    }

    // ── staging ────────────────────────────────────────────────────────────────────────

    private static StubCenter stage(JsonNode center) {
        if (center.has("transport")) {
            assertThat(center.path("transport").asText()).as("a transport this test can stage").isEqualTo("no-response");
            return StubCenter.hangingUp();
        }
        if (center.path("notCalled").asBoolean(false)) {
            // Never expected to answer; centerCalls 0 is asserted.
            return StubCenter.answering(StubCenter.Reply.of(418, ""));
        }
        assertThat(center.has("status")).as("center has neither notCalled, transport nor a status").isTrue();
        return StubCenter.answering(StubCenter.Reply.delayed(
                center.path("delayMs").asLong(0), center.path("status").asInt(), center.path("body").asText("")));
    }

    /**
     * The probe handler whose declaration resolves, through the production resolver, to
     * exactly {@code requires}. A requirement no probe declares fails loudly: add a probe.
     */
    private static HandlerMethod handlerDeclaring(String requires) {
        FixtureRoutes routes = new FixtureRoutes();
        for (Method method : FixtureRoutes.class.getDeclaredMethods()) {
            HandlerMethod handler = new HandlerMethod(routes, method);
            if (Declarations.of(handler) instanceof Declaration.Guarded guarded
                    && guarded.requirement().toString().equals(requires)) {
                return handler;
            }
        }
        throw new AssertionError("no FixtureRoutes probe declares \"" + requires + "\"; add one");
    }

    /**
     * The token part of a header, computed independently of the code under test: split on
     * Unicode whitespace (the regex engine's, not {@code Fields}), so a header the code
     * splits into two parts yields a token here too and the leak assertions check it.
     */
    private static String tokenOf(String authorization) {
        if (authorization == null) {
            return null;
        }
        String[] parts = authorization.strip().split("(?U)\\s+");
        return parts.length == 2 && parts[0].equalsIgnoreCase("bearer") ? parts[1] : null;
    }

    // ── the fixture ────────────────────────────────────────────────────────────────────

    private static void allowOnly(String where, JsonNode node, String... allowed) {
        assertThat(node.isObject()).as("%s is an object", where).isTrue();
        Set<String> known = Set.of(allowed);
        for (Iterator<String> keys = node.fieldNames(); keys.hasNext(); ) {
            String key = keys.next();
            assertThat(known).as("%s has key \"%s\", which this test does not know how to honour", where, key)
                    .contains(key);
        }
    }

    private static JsonNode required(JsonNode node, String pointer) {
        JsonNode value = node.at(pointer);
        assertThat(value.isMissingNode()).as("missing %s", pointer).isFalse();
        return value;
    }

    private static JsonNode fixture() throws IOException {
        return JSON.readTree(resource(FIXTURE));
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = IntrospectionConformanceTest.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(path + " is missing from the test classpath (see SOURCE.txt)");
            }
            return in.readAllBytes();
        }
    }
}
