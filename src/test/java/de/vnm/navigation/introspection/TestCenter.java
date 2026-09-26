package de.vnm.navigation.introspection;

import de.vnm.navigation.auth.Session;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The stand-in auth-service every web test points {@code auth.introspection.url} at: one
 * {@link StubCenter} per JVM, answering a fixed table of test tokens. It replaces the
 * per-run keypair the Clerk tests signed with — nothing in this service's tests signs a
 * token any more, and the tokens below are deliberately not JWTs.
 *
 * <p>Like the real center it refuses a request without the configured caller secret (401),
 * and answers {@code {"active":false}} for any token it does not know. Every web slice and
 * the {@code @SpringBootTest}s register it with {@link #register}; {@link #OPERATOR} is
 * exactly the {@link Session} {@link #OPERATOR_BEARER} is published as, so a service mock
 * can match on it by equality.
 */
public final class TestCenter {

    public static final String SECRET = "test-introspection-secret";

    /** An operator carrying {@code universe:refresh}. */
    public static final String OPERATOR_BEARER = "Bearer operator.universe-refresh";
    public static final Session OPERATOR = new Session("user_2TestOperator", "operator", List.of("universe:refresh"));

    /** An operator carrying only {@code fleet:control}, which does not imply {@code universe:refresh}. */
    public static final String FLEET_CONTROL_BEARER = "Bearer operator.fleet-control";
    public static final Session FLEET_CONTROLLER = new Session("user_2TestOperator", "operator", List.of("fleet:control"));

    /** A signed-in operator holding no scope at all, with the {@code scope} key absent. */
    public static final String SCOPELESS_BEARER = "Bearer operator.no-scopes";

    /** automation-service's M2M caller. */
    public static final String MACHINE_BEARER = "Bearer machine.universe-refresh";
    public static final Session MACHINE = new Session("mch_2TestMachine", "machine", List.of("universe:refresh"));

    /** Any token the center does not know is inactive; this one says so in its name. */
    public static final String INACTIVE_BEARER = "Bearer expired.token";

    /** A token the center answers 500 for: auth-service is broken, not the caller. */
    public static final String CENTER_FAILS_BEARER = "Bearer center.fails";

    private static final String ACTIVE = "{\"active\":true,\"sub\":\"%s\",%s\"exp\":4102444800,\"kind\":\"%s\"}";

    private static final Map<String, String> ANSWERS = Map.of(
            "operator.universe-refresh", ACTIVE.formatted("user_2TestOperator", "\"scope\":\"universe:refresh\",", "operator"),
            "operator.fleet-control", ACTIVE.formatted("user_2TestOperator", "\"scope\":\"fleet:control\",", "operator"),
            "operator.no-scopes", ACTIVE.formatted("user_2TestScopeless", "", "operator"),
            "machine.universe-refresh", ACTIVE.formatted("mch_2TestMachine", "\"scope\":\"universe:refresh\",", "machine"));

    private static StubCenter center;

    private TestCenter() {}

    /** The shared center, started on first use and left running for the life of the JVM. */
    public static synchronized StubCenter center() {
        if (center == null) {
            center = StubCenter.answering(TestCenter::answer);
        }
        return center;
    }

    /** Points the application at the shared center: call from a {@code @DynamicPropertySource}. */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("auth.introspection.url", () -> center().url());
        registry.add("auth.introspection.secret", () -> SECRET);
    }

    private static StubCenter.Reply answer(StubCenter.Received request) {
        if (!SECRET.equals(request.header(IntrospectionSettings.SECRET_HEADER))) {
            return StubCenter.Reply.of(401, "{\"error\":{\"message\":\"a valid introspection secret is required\"}}");
        }
        String token = request.body().startsWith("token=")
                ? URLDecoder.decode(request.body().substring("token=".length()), StandardCharsets.UTF_8)
                : "";
        if ("center.fails".equals(token)) {
            return StubCenter.Reply.of(500, "{\"error\":{\"message\":\"sql: database is locked\"}}");
        }
        String active = ANSWERS.get(token);
        return active == null ? StubCenter.Reply.inactive() : StubCenter.Reply.of(200, active);
    }
}
