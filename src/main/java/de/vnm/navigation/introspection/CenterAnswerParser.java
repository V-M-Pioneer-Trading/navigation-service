package de.vnm.navigation.introspection;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads the center's {@code 200} body into a {@link CenterAnswer}, strictly.
 *
 * <p>A partial or wrongly typed answer is <b>not</b> {@code active: false}: reading it that
 * way would turn a half-deployed center into a fleet-wide 401 storm and tell operators their
 * credentials were broken when they were not. It is a {@link NotTheContract}, which the
 * client turns into {@link CenterAnswer#UNAVAILABLE} and a 503.
 *
 * <p>The contract, key by key, spelled exactly and in lower case:
 *
 * <ul>
 *   <li>{@code active} — required, a JSON boolean. {@code "true"} and {@code 1} are not.</li>
 *   <li>{@code sub} — required when active, a non-empty string.</li>
 *   <li>{@code exp} — required when active, a finite JSON number ({@code 1e400} is not).
 *       Checked for shape only: expiry is the center's decision, and a client that
 *       re-judged it would be a second verifier.</li>
 *   <li>{@code kind} — required when active, {@code "operator"} or {@code "machine"}; used
 *       verbatim, never re-derived from {@code sub}. agent-service and the TypeScript client
 *       refuse any other value too, so the family agrees on what a kind is.</li>
 *   <li>{@code scope} — <b>optional</b> (RFC 7662; fixture version 3): absent reads exactly
 *       as {@code ""}. Present, it must be a string.</li>
 * </ul>
 *
 * <p>A {@code null} is malformed wherever a rule reads the key, and nowhere else. Across the
 * whole object: a key appearing twice in any spelling ({@code {"active":false,"Active":true}}),
 * a contract key in any spelling but its own, anything but a single top-level object, and
 * trailing bytes after it are all malformed. Unknown extension keys (RFC 7662 §2.2 allows them) are ignored.
 */
public final class CenterAnswerParser {

    private static final Set<String> CONTRACT_KEYS = Set.of("active", "sub", "scope", "exp", "kind");
    private static final Set<String> KINDS = Set.of("operator", "machine");

    /**
     * A mapper of its own rather than Spring's: this reading must not change because
     * somebody tuned the application's JSON settings for controllers.
     */
    private static final JsonMapper STRICT = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private CenterAnswerParser() {}

    /**
     * @throws NotTheContract when {@code body} is not a well-formed introspection answer. The
     *                        message names the rule broken, never a value from the body.
     */
    public static CenterAnswer parse(byte[] body) throws NotTheContract {
        JsonNode root;
        try {
            root = STRICT.readTree(body);
        } catch (JacksonException e) {
            // Jackson's message quotes the offending bytes; ours does not.
            throw new NotTheContract("not a single well-formed JSON value");
        } catch (IOException e) {
            throw new NotTheContract("unreadable");
        }
        if (root == null || !root.isObject()) {
            throw new NotTheContract("top level is not an object");
        }
        checkKeySpelling(root);

        JsonNode active = root.get("active");
        if (active == null || !active.isBoolean()) {
            throw new NotTheContract("no boolean `active`");
        }
        if (!active.booleanValue()) {
            return CenterAnswer.INACTIVE;
        }

        JsonNode sub = root.get("sub");
        if (sub == null || !sub.isTextual() || sub.textValue().isEmpty()) {
            throw new NotTheContract("active without a non-empty string `sub`");
        }
        JsonNode exp = root.get("exp");
        if (exp == null || !exp.isNumber() || !Double.isFinite(exp.doubleValue())) {
            throw new NotTheContract("active without a finite numeric `exp`");
        }
        JsonNode kind = root.get("kind");
        if (kind == null || !kind.isTextual() || !KINDS.contains(kind.textValue())) {
            throw new NotTheContract("active without a known `kind`");
        }
        JsonNode scope = root.get("scope");
        if (scope != null && !scope.isTextual()) {
            // Absent is the empty list; present-but-null or present-but-not-a-string is a
            // center we do not understand.
            throw new NotTheContract("`scope` is present but not a string");
        }

        String scopes = scope == null ? "" : scope.textValue();
        return new CenterAnswer.Active(new Identity(sub.textValue(), kind.textValue(), Fields.ascii(scopes)));
    }

    /**
     * Refuses a key repeated in any spelling, and a contract key in any spelling but its
     * own. A struct-binding reader matches keys case-insensitively and lets the last
     * duplicate win, so {@code {"active":false,"Active":true}} would bind {@code active=true};
     * here it makes the whole answer unusable instead. A JSON {@code null} is judged only
     * where a rule reads the key, as agent-service judges it: {@code {"active":false,"sub":null}}
     * is an inactive token, and a {@code null} {@code sub} on an active answer is malformed.
     */
    private static void checkKeySpelling(JsonNode root) throws NotTheContract {
        Set<String> seen = new HashSet<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> member = it.next();
            String key = member.getKey();
            String folded = key.toLowerCase(Locale.ROOT);
            if (!seen.add(folded)) {
                throw new NotTheContract("a key appears more than once, ignoring case");
            }
            if (CONTRACT_KEYS.contains(folded) && !key.equals(folded)) {
                throw new NotTheContract("`" + folded + "` is not spelled in lower case");
            }
        }
    }

    /** The center answered 2xx with a body that is not the introspection contract. */
    public static final class NotTheContract extends Exception {

        NotTheContract(String reason) {
            super(reason, null, false, false);
        }
    }
}
