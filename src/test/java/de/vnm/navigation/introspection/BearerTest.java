package de.vnm.navigation.introspection;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What is a bearer token, and what is not (token-introspection.md). Everything in the
 * second group reads as no credential at all — which the policy answers with
 * {@code 401 a bearer token is required} and no call to the center.
 */
class BearerTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "Bearer abc.def.ghi",
            "bearer abc.def.ghi",       // RFC 7235: the scheme is case-insensitive
            "BEARER abc.def.ghi",
            "Bearer   abc.def.ghi",     // a run of spaces is one separator
            "Bearer\tabc.def.ghi",
            "  Bearer abc.def.ghi  ",
    })
    void schemePlusExactlyOneTokenIsACredential(String header) {
        assertThat(Bearer.tokenFrom(header)).contains("abc.def.ghi");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "   ",
            "Bearer",
            "Bearer ",
            "Bearer abc def",             // never "abcdef", never "abc def"
            "Bearer a, Bearer b",         // two header lines, joined: four parts
            "Bearer a,Bearer b",          // three parts
            "Basic b3BlcmF0b3I6aHVudGVyMg==",
            "Token abc",
            "Bearerabc",
            "abc",
    })
    void anythingElseIsNoCredential(String header) {
        assertThat(Bearer.tokenFrom(header)).isEmpty();
    }

    /**
     * Regression: {@code ClerkAuthFilter.bearerFrom} split with a limit of two and kept the
     * remainder, so {@code "Bearer abc def"} became the token {@code "abc def"} and was
     * forwarded into verification. The comma-joined pair is the same bug with a different
     * header: it would have become the token {@code "a, Bearer b"}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Bearer abc def", "Bearer a, Bearer b"})
    void aHeaderIsNeverSplitWithALimit(String header) {
        assertThat(Bearer.tokenFrom(header)).isEmpty();
    }

    /**
     * Every ASCII whitespace character Go's {@code strings.Fields} splits on separates here
     * too, vertical tab and form feed included.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Bearer\u000Babc", "Bearer\fabc", "Bearer\tabc", "Bearer \r\nabc"})
    void asciiWhitespaceSeparatesTheSchemeFromTheToken(String header) {
        assertThat(Bearer.tokenFrom(header)).contains("abc");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer abc\u000Bdef", "Bearer abc\fdef"})
    void asciiWhitespaceInsideTheTokenMakesAThirdPart(String header) {
        assertThat(Bearer.tokenFrom(header)).isEmpty();
    }

    /**
     * Any non-ASCII character makes the header no credential. A token68 is ASCII, and a
     * non-ASCII byte could not be relayed to st-gateway byte for byte: it arrived as {@code ?}.
     * Regression: a no-break space or NEL used to separate the scheme from the token, and
     * {@code Bearer caf\u00e9} was forwarded to the center and then, mangled, upstream.
     */
    @ParameterizedTest
    @ValueSource(strings = {"Bearer\u00A0abc", "Bearer\u0085abc", "Bearer\u2003abc",
            "\u3000Bearer abc", "Bearer abc\u2028", "Bearer caf\u00e9", "Bearer abc\u00A0def"})
    void anyNonAsciiCharacterIsNoCredential(String header) {
        assertThat(Bearer.tokenFrom(header)).isEmpty();
    }
}
