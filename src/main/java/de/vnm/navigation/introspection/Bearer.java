package de.vnm.navigation.introspection;

import java.util.List;
import java.util.Optional;

/**
 * Reads a bearer token out of an {@code Authorization} header value — and reads nothing
 * else into one.
 *
 * <p>A credential is <b>exactly</b> the scheme plus one token68: two whitespace-separated
 * parts, the scheme compared case-insensitively (RFC 7235), so {@code bearer abc} is a
 * credential. "Whitespace" is Unicode whitespace, exactly as agent-service's
 * {@code strings.Fields} and the TypeScript client's {@code /\s+/} read it, so
 * a vertical tab (U+000B) between scheme and token separates them, and a no-break space
 * (U+00A0) inside the token splits it into a third part. Everything else is no credential
 * at all, which the policy answers with
 * {@code 401 a bearer token is required} <i>without</i> calling the center:
 *
 * <ul>
 *   <li>{@code "Bearer"} and {@code "Bearer "} carry no token. Forwarding one would POST
 *       {@code token=} and spend a round trip on the way to the same answer.</li>
 *   <li>{@code "Bearer abc def"} is neither the token {@code abcdef} nor {@code abc def}.
 *       A header is never concatenated, and never split with a limit so the remainder
 *       survives intact — which is exactly what this service's own filter did before
 *       decision 21.</li>
 *   <li>Two {@code Authorization} header lines arrive here joined, as
 *       {@code "Bearer a, Bearer b"}: four parts, so none. Picking one would let a caller
 *       choose which of two credentials a proxy sees this service verify.</li>
 *   <li>Any other scheme — {@code Basic …} — is not forwarded either.</li>
 * </ul>
 *
 * <p>The token itself is opaque: never parsed, decoded, validated or logged.
 */
public final class Bearer {

    private static final String SCHEME = "Bearer";

    private Bearer() {}

    /**
     * @param authorization the header value, or {@code null} when the request carried none
     * @return the token, or empty when the value is not exactly {@code <bearer> <token>}
     */
    public static Optional<String> tokenFrom(String authorization) {
        if (authorization == null) {
            return Optional.empty();
        }
        List<String> parts = Fields.unicode(authorization);
        if (parts.size() != 2 || !parts.get(0).equalsIgnoreCase(SCHEME)) {
            return Optional.empty();
        }
        return Optional.of(parts.get(1));
    }
}
