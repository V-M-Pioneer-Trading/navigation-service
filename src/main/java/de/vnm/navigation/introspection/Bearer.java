package de.vnm.navigation.introspection;

import java.util.List;
import java.util.Optional;

/**
 * Reads a bearer token out of an {@code Authorization} header value — and reads nothing
 * else into one.
 *
 * <p>A credential is <b>exactly</b> the scheme plus one token68: two whitespace-separated
 * parts, the scheme compared case-insensitively (RFC 7235), so {@code bearer abc} is a
 * credential. Everything else is no credential at all, which the policy answers with
 * {@code 401 a bearer token is required} <i>without</i> calling the center:
 *
 * <ul>
 *   <li>{@code "Bearer"} and {@code "Bearer "} carry no token. Forwarding one would POST
 *       {@code token=} and spend a round trip on the way to the same answer.</li>
 *   <li>{@code "Bearer abc def"} is neither the token {@code abcdef} nor {@code abc def}.
 *       A header is never concatenated, and never split with a limit so the remainder
 *       survives intact — which is exactly what this service's own filter did before
 *       decision 21.</li>
 *   <li>{@code "Bearer a, Bearer b"}, the way a proxy folds two lines, is four parts, so
 *       none. (Two lines reaching this service are refused before they get here.)</li>
 *   <li>Any other scheme — {@code Basic …} — is not forwarded either.</li>
 *   <li>A header holding <b>any character outside ASCII</b>. A token68 is ASCII by
 *       definition, and Tomcat decodes header bytes as ISO-8859-1, so a non-ASCII byte
 *       could not survive the byte-for-byte relay to st-gateway anyway — it arrived there
 *       as {@code ?}. Refusing it also settles every Unicode-whitespace question: what is
 *       left to split is ASCII, where this reads exactly as Go's {@code strings.Fields}.</li>
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
        if (authorization == null || authorization.chars().anyMatch(c -> c > 0x7F)) {
            return Optional.empty();
        }
        List<String> parts = Fields.whitespace(authorization);
        if (parts.size() != 2 || !parts.get(0).equalsIgnoreCase(SCHEME)) {
            return Optional.empty();
        }
        return Optional.of(parts.get(1));
    }
}
