package de.vnm.navigation.auth;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The verifier on its own: every reason a token can be refused, and what a good one yields. */
class ClerkVerifierTest {

    private final ClerkVerifier verifier = new ClerkVerifier(TestClerk.publicKeyPem(), null);

    private static String raw(String bearer) {
        return bearer.substring("Bearer ".length());
    }

    @Test
    void aValidToken_yieldsSubjectAndScopes() throws Exception {
        Session session = verifier.verify(raw(TestClerk.bearer("universe:refresh", "fleet:control")));

        assertThat(session.subject()).isEqualTo(TestClerk.ACTOR);
        assertThat(session.scopes()).containsExactlyInAnyOrder("universe:refresh", "fleet:control");
        assertThat(session.hasScope("universe:refresh")).isTrue();
        assertThat(session.hasScope("agent:reset")).isFalse();
    }

    @Test
    void anEmptyScopeClaim_yieldsNoScopes() throws Exception {
        Session session = verifier.verify(raw(TestClerk.bearerWithoutScope()));

        assertThat(session.scopes()).isEmpty();
    }

    @Test
    void aTokenSignedByAnUnknownKey_isRejected() {
        assertThatThrownBy(() -> verifier.verify(raw(TestClerk.foreignBearer())))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void anExpiredToken_isRejected() {
        assertThatThrownBy(() -> verifier.verify(raw(TestClerk.expiredBearer())))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void garbage_isRejectedNotThrownAsSomethingElse() {
        assertThatThrownBy(() -> verifier.verify("not.a.jwt"))
                .isInstanceOf(InvalidSessionException.class);
        assertThatThrownBy(() -> verifier.verify(""))
                .isInstanceOf(InvalidSessionException.class);
    }

    @Test
    void issuerIsCheckedOnlyWhenConfigured() throws Exception {
        ClerkVerifier strict = new ClerkVerifier(TestClerk.publicKeyPem(), "https://clerk.example");

        String right = TestClerk.token(TestClerk.DEFAULT_SCOPES, TestClerk.ACTOR, 300, "https://clerk.example");
        String wrong = TestClerk.token(TestClerk.DEFAULT_SCOPES, TestClerk.ACTOR, 300, "https://other.example");
        String none = TestClerk.token(TestClerk.DEFAULT_SCOPES, TestClerk.ACTOR, 300, null);

        assertThat(strict.verify(right).subject()).isEqualTo(TestClerk.ACTOR);
        assertThatThrownBy(() -> strict.verify(wrong)).isInstanceOf(InvalidSessionException.class);
        assertThatThrownBy(() -> strict.verify(none)).isInstanceOf(InvalidSessionException.class);
        // The lenient verifier accepts all three: the key is what verifies, the issuer only narrows.
        assertThat(verifier.verify(wrong).subject()).isEqualTo(TestClerk.ACTOR);
    }

    /** The key arrives through a one-line env var in production, with literal backslash-n. */
    @Test
    void publicKeyWithEscapedNewlines_isAccepted() throws Exception {
        String oneLine = TestClerk.publicKeyPem().replace("\n", "\\n");

        Session session = new ClerkVerifier(oneLine, null).verify(raw(TestClerk.bearer()));

        assertThat(session.subject()).isEqualTo(TestClerk.ACTOR);
    }

    @Test
    void missingOrUnparseableKey_refusesToConstruct() {
        assertThatThrownBy(() -> new ClerkVerifier("", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trust anchor");
        assertThatThrownBy(() -> new ClerkVerifier("-----BEGIN PUBLIC KEY-----\nnope\n-----END PUBLIC KEY-----", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scopesMayArriveAsAnArray() throws Exception {
        // Clerk emits a space-delimited string; the TS copies tolerate an array too, so this
        // one does, to keep the family byte-for-byte equivalent on claim shape.
        com.nimbusds.jwt.JWTClaimsSet claims = new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .subject("user_arr")
                .claim("scope", List.of("universe:refresh", "fleet:control"))
                .expirationTime(new java.util.Date(System.currentTimeMillis() + 60_000))
                .build();
        String token = TestClerk.signClaims(claims);

        assertThat(verifier.verify(token).scopes()).containsExactlyInAnyOrder("universe:refresh", "fleet:control");
    }
}
