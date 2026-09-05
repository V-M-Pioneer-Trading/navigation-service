package de.vnm.navigation.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.text.ParseException;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Networkless Clerk session verification (auth-design.md decision 10).
 *
 * <p>Same shape as fleet-service's {@code auth.ts}, which is the canonical copy: RS256
 * pinned, the PEM public key from {@code CLERK_JWT_KEY} as the sole trust anchor, an
 * optional {@code iss} check, and a {@code scope} claim that may arrive as a
 * space-delimited string or an array. No JWKS fetch, no bypass flag; only the key differs
 * between local, CI and production.
 */
public final class ClerkVerifier {

    private static final JWSAlgorithm ALGORITHM = JWSAlgorithm.RS256;

    private final RSASSAVerifier signatureVerifier;
    private final String expectedIssuer;
    private final Clock clock;

    /**
     * @param publicKeyPem   SPKI PEM ({@code -----BEGIN PUBLIC KEY-----}); literal
     *                       {@code \n} sequences are accepted, since that is how the key
     *                       survives a one-line environment variable
     * @param expectedIssuer the {@code iss} every token must carry, or {@code null} to
     *                       skip the check
     */
    public ClerkVerifier(String publicKeyPem, String expectedIssuer) {
        this(publicKeyPem, expectedIssuer, Clock.systemUTC());
    }

    ClerkVerifier(String publicKeyPem, String expectedIssuer, Clock clock) {
        this.signatureVerifier = new RSASSAVerifier(parsePublicKey(publicKeyPem));
        this.expectedIssuer = expectedIssuer == null || expectedIssuer.isBlank() ? null : expectedIssuer;
        this.clock = clock;
    }

    /**
     * Verify a compact JWT and return the identity it proves.
     *
     * @throws InvalidSessionException for any reason at all: malformed, wrong algorithm,
     *                                 bad signature, expired, not yet valid, wrong issuer
     */
    public Session verify(String token) throws InvalidSessionException {
        SignedJWT jwt;
        try {
            jwt = SignedJWT.parse(token);
        } catch (ParseException e) {
            throw new InvalidSessionException("malformed token", e);
        }

        if (!ALGORITHM.equals(jwt.getHeader().getAlgorithm())) {
            throw new InvalidSessionException("unexpected algorithm " + jwt.getHeader().getAlgorithm());
        }

        try {
            if (!jwt.verify(signatureVerifier)) {
                throw new InvalidSessionException("signature did not verify");
            }
        } catch (JOSEException e) {
            throw new InvalidSessionException("signature could not be checked", e);
        }

        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            throw new InvalidSessionException("unreadable claims", e);
        }

        Date now = Date.from(clock.instant());
        Date exp = claims.getExpirationTime();
        if (exp == null || !exp.after(now)) {
            throw new InvalidSessionException("expired");
        }
        Date nbf = claims.getNotBeforeTime();
        if (nbf != null && nbf.after(now)) {
            throw new InvalidSessionException("not yet valid");
        }
        if (expectedIssuer != null && !expectedIssuer.equals(claims.getIssuer())) {
            throw new InvalidSessionException("wrong issuer");
        }

        String subject = claims.getSubject();
        return new Session(subject == null ? "" : subject, scopesFrom(claims.getClaim("scope")));
    }

    /** The claim is a space-delimited string from Clerk; tolerate an array too, as the TS copies do. */
    private static Set<String> scopesFrom(Object claim) {
        Set<String> scopes = new LinkedHashSet<>();
        if (claim instanceof String s) {
            for (String part : s.trim().split("\\s+")) {
                if (!part.isEmpty()) scopes.add(part);
            }
        } else if (claim instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isEmpty()) scopes.add(s);
            }
        }
        return scopes;
    }

    static RSAPublicKey parsePublicKey(String pem) {
        if (pem == null || pem.isBlank()) {
            throw new IllegalArgumentException("Clerk public key is required — refusing to start without a trust anchor");
        }
        String body = pem.replace("\\n", "\n")
                         .replace("-----BEGIN PUBLIC KEY-----", "")
                         .replace("-----END PUBLIC KEY-----", "")
                         .replaceAll("\\s", "");
        try {
            byte[] der = Base64.getDecoder().decode(body);
            return (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalArgumentException("Clerk public key is not a valid SPKI PEM RSA key", e);
        }
    }
}
