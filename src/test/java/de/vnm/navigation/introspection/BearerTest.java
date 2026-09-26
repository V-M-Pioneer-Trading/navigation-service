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

    /** A Unicode space is not a separator: the token is opaque and the center decides. */
    @ParameterizedTest
    @ValueSource(strings = {"Bearer abc def"})
    void unicodeSpacesAreNotSeparators(String header) {
        assertThat(Bearer.tokenFrom(header)).contains("abc def");
    }
}
