package de.vnm.navigation.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Test credentials: an ephemeral keypair, generated per test run (auth-design.md
 * decision 10). Same shape as fleet-service's {@code testSupport/authTokens.ts}: tests
 * exercise the real verification path, and only the trust anchor differs from production.
 *
 * <p>Controller tests point {@code clerk.jwt-key} at {@link #publicKeyPem()} through a
 * {@code @DynamicPropertySource}; {@link #OPERATOR} is exactly the {@link Session} that
 * {@link #bearer()} verifies to, so a mock can match on it by equality.
 */
public final class TestClerk {

    private static final KeyPair KEY_PAIR = newKeyPair();
    /** A second, untrusted keypair — the service is never told about this one. */
    private static final KeyPair FOREIGN = newKeyPair();

    public static final String ACTOR = "user_2TestOperator";
    public static final List<String> DEFAULT_SCOPES = List.of(Scopes.UNIVERSE_REFRESH);

    /** The session {@link #bearer()} proves. */
    public static final Session OPERATOR = new Session(ACTOR, Set.copyOf(DEFAULT_SCOPES));

    private TestClerk() {}

    public static String publicKeyPem() {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes())
                              .encodeToString(KEY_PAIR.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + base64 + "\n-----END PUBLIC KEY-----\n";
    }

    /** A ready-to-use {@code Authorization} value for an operator with the default scopes. */
    public static String bearer() {
        return "Bearer " + token(DEFAULT_SCOPES, ACTOR, 300, null);
    }

    public static String bearer(String... scopes) {
        return "Bearer " + token(List.of(scopes), ACTOR, 300, null);
    }

    /** A signed-in operator who holds no scope at all. */
    public static String bearerWithoutScope() {
        return "Bearer " + token(List.of(), ACTOR, 300, null);
    }

    /** A well-formed token whose {@code exp} has already passed. */
    public static String expiredBearer() {
        return "Bearer " + token(DEFAULT_SCOPES, ACTOR, -60, null);
    }

    /**
     * Correctly shaped, correct scopes, valid {@code exp} — signed by a key the service has
     * never seen. The one token that proves the signature is checked rather than the
     * payload merely decoded.
     */
    public static String foreignBearer() {
        return "Bearer " + sign((RSAPrivateKey) FOREIGN.getPrivate(), DEFAULT_SCOPES, ACTOR, 300, null);
    }

    /** Sign an arbitrary claim set with the trusted key — for claim-shape edge cases. */
    public static String signClaims(JWTClaimsSet claims) {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        try {
            jwt.sign(new RSASSASigner((RSAPrivateKey) KEY_PAIR.getPrivate()));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    public static String token(List<String> scopes, String sub, long expiresInSeconds, String issuer) {
        return sign((RSAPrivateKey) KEY_PAIR.getPrivate(), scopes, sub, expiresInSeconds, issuer);
    }

    private static String sign(RSAPrivateKey key, List<String> scopes, String sub,
                               long expiresInSeconds, String issuer) {
        long now = System.currentTimeMillis();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(sub)
                .claim("scope", String.join(" ", scopes))
                .issueTime(new Date(now))
                .expirationTime(new Date(now + expiresInSeconds * 1000));
        if (issuer != null) claims.issuer(issuer);

        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        try {
            jwt.sign(new RSASSASigner(key));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        return jwt.serialize();
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
