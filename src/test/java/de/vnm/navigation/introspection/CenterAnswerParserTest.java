package de.vnm.navigation.introspection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The strict reading of the center's answer. Everything in the "malformed" groups must be
 * {@link CenterAnswerParser.NotTheContract} — a 503 — and never {@code active: false}, which
 * would turn a half-deployed center into a fleet-wide 401 storm.
 */
class CenterAnswerParserTest {

    private static final String ACTIVE =
            "{\"active\":true,\"sub\":\"user_1\",\"scope\":\"fleet:control\",\"exp\":4102444800,\"kind\":\"operator\"}";

    // ── well-formed ────────────────────────────────────────────────────────────────────

    @Test
    void anActiveAnswerBecomesTheIdentityVerbatim() throws Exception {
        assertThat(parse(ACTIVE)).isEqualTo(
                new CenterAnswer.Active(new Identity("user_1", "operator", List.of("fleet:control"))));
    }

    @Test
    void inactiveNeedsNothingButActive() throws Exception {
        assertThat(parse("{\"active\":false}")).isEqualTo(CenterAnswer.INACTIVE);
    }

    /** Fixture version 3: RFC 7662 makes {@code scope} optional; absent means no scopes. */
    @Test
    void anAbsentScopeIsTheEmptyList() throws Exception {
        CenterAnswer answer = parse("{\"active\":true,\"sub\":\"user_1\",\"exp\":1,\"kind\":\"operator\"}");
        assertThat(answer).isEqualTo(new CenterAnswer.Active(new Identity("user_1", "operator", List.of())));
    }

    @Test
    void anEmptyScopeIsTheEmptyListNotOneEmptyScope() throws Exception {
        CenterAnswer answer = parse("{\"active\":true,\"sub\":\"user_1\",\"scope\":\"\",\"exp\":1,\"kind\":\"machine\"}");
        assertThat(((CenterAnswer.Active) answer).identity().scopes()).isEmpty();
    }

    @Test
    void scopeIsSplitOnAsciiWhitespaceRunsWithEmptiesDropped() throws Exception {
        CenterAnswer answer = parse("{\"active\":true,\"sub\":\"u\",\"scope\":\"  a  b\\tc\\r\\nd \",\"exp\":1,\"kind\":\"operator\"}");
        assertThat(((CenterAnswer.Active) answer).identity().scopes()).containsExactly("a", "b", "c", "d");
    }

    @Test
    void aUnicodeSpaceInsideAScopeIsPartOfTheScope() throws Exception {
        CenterAnswer answer = parse("{\"active\":true,\"sub\":\"u\",\"scope\":\"a\\u00a0b c\",\"exp\":1,\"kind\":\"operator\"}");
        assertThat(((CenterAnswer.Active) answer).identity().scopes()).containsExactly("a b", "c");
    }

    /** RFC 7662 §2.2 allows extension members; they are not ours to refuse. */
    @Test
    void unknownExtensionKeysAreIgnored() throws Exception {
        CenterAnswer answer = parse("{\"active\":true,\"sub\":\"u\",\"exp\":1,\"kind\":\"operator\",\"iat\":0,\"client_id\":null}");
        assertThat(answer).isInstanceOf(CenterAnswer.Active.class);
    }

    @Test
    void kindIsTakenFromTheCenterNeverFromTheSubject() throws Exception {
        CenterAnswer answer = parse("{\"active\":true,\"sub\":\"user_1\",\"exp\":1,\"kind\":\"machine\"}");
        assertThat(((CenterAnswer.Active) answer).identity().kind()).isEqualTo("machine");
    }

    // ── malformed: shape ───────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "<html><body>503 Service Unavailable</body></html>",
            "[]",
            "\"active\"",
            "true",
            "null",
            "{\"active\":false} trailing",
            "{\"active\":false}{\"active\":true}",
            "{\"active\":false",
    })
    void anythingButOneObjectIsNotTheContract(String body) {
        assertMalformed(body);
    }

    // ── malformed: duplicates and spelling ─────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"active\":false,\"active\":true}",
            "{\"active\":false,\"Active\":true}",
            "{\"active\":true,\"sub\":\"a\",\"sub\":\"b\",\"exp\":1,\"kind\":\"operator\"}",
            "{\"active\":true,\"sub\":\"u\",\"exp\":1,\"kind\":\"operator\",\"scope\":\"x\",\"SCOPE\":\"y\"}",
            "{\"active\":false,\"iat\":1,\"IAT\":2}",
    })
    void aKeyRepeatedInAnySpellingIsNotTheContract(String body) {
        assertMalformed(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"Active\":true,\"sub\":\"u\",\"exp\":1,\"kind\":\"operator\"}",
            "{\"active\":true,\"Sub\":\"u\",\"exp\":1,\"kind\":\"operator\"}",
            "{\"active\":true,\"sub\":\"u\",\"EXP\":1,\"kind\":\"operator\"}",
            "{\"active\":true,\"sub\":\"u\",\"exp\":1,\"Kind\":\"operator\"}",
            "{\"active\":true,\"sub\":\"u\",\"exp\":1,\"kind\":\"operator\",\"Scope\":\"fleet:control\"}",
    })
    void aContractKeySpelledInAnotherCaseIsNotTheContract(String body) {
        assertMalformed(body);
    }

    // ── malformed: null and wrong types ────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"active\":null}",
            "{\"active\":true,\"sub\":null,\"exp\":1,\"kind\":\"operator\"}",
            "{\"active\":true,\"sub\":\"u\",\"exp\":null,\"kind\":\"operator\"}",
            "{\"active\":true,\"sub\":\"u\",\"exp\":1,\"kind\":null}",
            "{\"active\":true,\"sub\":\"u\",\"exp\":1,\"kind\":\"operator\",\"scope\":null}",
            "{\"active\":false,\"scope\":null}",
    })
    void aNullContractValueIsNotTheContract(String body) {
        assertMalformed(body);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"active\":\"true\"}",
            "{\"active\":1}",
            "{\"active\":\"false\"}",
            "{\"active\":0}",
            "{\"active\":true,\"exp\":1,\"kind\":\"operator\"}",
            "{\"active\":true,\"sub\":\"\",\"exp\":1,\"kind\":\"operator\"}",
            "{\"active\":true,\"sub\":42,\"exp\":1,\"kind\":\"operator\"}",
            "{\"active\":true,\"sub\":\"u\",\"kind\":\"operator\"}",
            "{\"active\":true,\"sub\":\"u\",\"exp\":\"4102444800\",\"kind\":\"operator\"}",
            "{\"active\":true,\"sub\":\"u\",\"exp\":1}",
            "{\"active\":true,\"sub\":\"u\",\"exp\":1,\"kind\":\"Operator\"}",
            "{\"active\":true,\"sub\":\"u\",\"exp\":1,\"kind\":\"robot\"}",
            "{\"active\":true,\"sub\":\"u\",\"exp\":1,\"kind\":[\"operator\"]}",
            "{\"active\":true,\"sub\":\"u\",\"exp\":1,\"kind\":\"operator\",\"scope\":[\"fleet:control\"]}",
            "{\"active\":true,\"sub\":\"u\",\"exp\":1,\"kind\":\"operator\",\"scope\":7}",
            "{\"active\":true,\"sub\":\"u\",\"exp\":1,\"kind\":\"operator\",\"scope\":{}}",
    })
    void aMissingOrWronglyTypedMemberIsNotTheContract(String body) {
        assertMalformed(body);
    }

    @Test
    void theReasonNeverQuotesTheBody() {
        assertThatThrownBy(() -> parse("<html>secret-looking-proxy-page</html>"))
                .isInstanceOf(CenterAnswerParser.NotTheContract.class)
                .hasMessageNotContaining("secret-looking-proxy-page")
                .hasMessageNotContaining("html");
    }

    private static CenterAnswer parse(String body) throws CenterAnswerParser.NotTheContract {
        return CenterAnswerParser.parse(body.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertMalformed(String body) {
        assertThatThrownBy(() -> parse(body)).isInstanceOf(CenterAnswerParser.NotTheContract.class);
    }
}
