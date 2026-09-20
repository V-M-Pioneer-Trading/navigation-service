package de.vnm.navigation.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * The one authorization rule for {@code /api/navigation/v1/**} (auth-design.md decisions
 * 2, 3 and 20):
 *
 * <ul>
 *   <li>{@code GET}: public. A verified session, if one is presented, is handed to the
 *       controller as the {@link #SESSION_ATTRIBUTE} request attribute so the service can
 *       decide whether a live fetch is allowed; no header at all means anonymous.</li>
 *   <li>Alongside it, the raw {@code Authorization} header is republished as
 *       {@link #CALLER_AUTHORIZATION_ATTRIBUTE} so it can be forwarded verbatim to
 *       st-gateway, which derives the queue lane from the identity it verifies for itself
 *       (auth-design.md decision 2). Set here, next to the verification that earned it,
 *       and only on the success path: an unverified credential is never relayed.</li>
 *   <li>Anything else (the {@code POST …/refresh} routes): requires a verified session
 *       carrying {@link Scopes#UNIVERSE_REFRESH}.</li>
 *   <li>A presented token that does not verify is a 401 on every method — a bad
 *       credential is never quietly downgraded to anonymous.</li>
 * </ul>
 *
 * <p>Error bodies use the family's {@code {"error":{"message":…}}} envelope and wording,
 * byte-identical to fleet-service and agent-service, so command-interface needs one
 * parser for every backend. This is a deliberate exception to the RFC 9457 problem
 * details the rest of this service emits.
 */
public final class ClerkAuthFilter extends OncePerRequestFilter {

    public static final String SESSION_ATTRIBUTE = "de.vnm.navigation.auth.Session";

    /**
     * The inbound {@code Authorization} header, byte for byte, published only when the
     * session it carries verified. A plain {@code String} rather than a richer type
     * because {@code client} may not import from {@code auth}, and because the thing
     * being forwarded really is just those bytes.
     *
     * <p>It is the Clerk session and nothing else. No SpaceTraders game token exists in
     * this service to confuse it with (auth-design.md decision 5).
     */
    public static final String CALLER_AUTHORIZATION_ATTRIBUTE =
            "de.vnm.navigation.auth.CallerAuthorization";

    static final String GUARDED_PREFIX = "/api/navigation/v1/";

    static final String MISSING_TOKEN = "a bearer token is required";
    static final String INVALID_SESSION = "invalid or expired session";
    static final String MISSING_SCOPE = "this action requires a scope this session does not carry";

    private final ClerkVerifier verifier;

    public ClerkAuthFilter(ClerkVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(GUARDED_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        boolean mutating = !HttpMethod.GET.matches(request.getMethod())
                && !HttpMethod.HEAD.matches(request.getMethod())
                && !HttpMethod.OPTIONS.matches(request.getMethod());

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = bearerFrom(authorization);
        if (token == null) {
            if (mutating) {
                reject(response, HttpServletResponse.SC_UNAUTHORIZED, MISSING_TOKEN);
                return;
            }
            chain.doFilter(request, response);
            return;
        }

        Session session;
        try {
            session = verifier.verify(token);
        } catch (InvalidSessionException e) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, INVALID_SESSION);
            return;
        }

        if (mutating && !session.hasScope(Scopes.UNIVERSE_REFRESH)) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, MISSING_SCOPE);
            return;
        }

        request.setAttribute(SESSION_ATTRIBUTE, session);
        // Verbatim, including the scheme: st-gateway re-verifies the same bytes, so any
        // reconstruction here would only be a chance to get it wrong. Set in the same
        // breath as the session so the two can never disagree about who is calling.
        request.setAttribute(CALLER_AUTHORIZATION_ATTRIBUTE, authorization);
        chain.doFilter(request, response);
    }

    /** {@code Bearer <token>}, scheme case-insensitive; anything else reads as no token. */
    static String bearerFrom(String header) {
        if (header == null) return null;
        String[] parts = header.trim().split("\\s+", 2);
        if (parts.length != 2 || !parts[0].equalsIgnoreCase("Bearer")) return null;
        String token = parts[1].trim();
        return token.isEmpty() ? null : token;
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":{\"message\":\"" + message + "\"}}");
    }
}
