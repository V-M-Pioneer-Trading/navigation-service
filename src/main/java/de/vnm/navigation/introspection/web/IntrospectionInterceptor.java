package de.vnm.navigation.introspection.web;

import de.vnm.navigation.auth.CallerAttributes;
import de.vnm.navigation.auth.Session;
import de.vnm.navigation.introspection.AccessPolicy;
import de.vnm.navigation.introspection.Decision;
import de.vnm.navigation.introspection.Identity;
import de.vnm.navigation.introspection.Rejection;
import de.vnm.navigation.introspection.SafeMethods;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

/**
 * The one global enforcement point: every request Spring MVC dispatches to a handler passes
 * through here first, and is either refused with the family envelope or handed to the
 * handler with the verified caller attached.
 *
 * <p>A {@code HandlerInterceptor} rather than a servlet filter because it runs <i>after</i>
 * Spring has matched the request and therefore sees the resolved handler — the only place
 * the handler's own declaration can be read. A filter would have to guess the route from
 * the path, which is a second matcher that can disagree with the first.
 *
 * <p>How Spring's own dispatch maps onto the fixture:
 *
 * <ul>
 *   <li>{@code HEAD} is dispatched by Spring to the {@code GET} handler of the same path,
 *       so it arrives here with that handler — and that handler's declaration. The method
 *       seen here is still {@code HEAD}, which is safe, so it is exempt from default-deny
 *       exactly as {@code GET} is and from nothing else ({@code head-*} cases).</li>
 *   <li>An {@code OPTIONS} no handler declared is answered by Spring's built-in responder,
 *       declared {@code none}: a visitor proceeds, a bearer is still verified
 *       ({@code options-with-no-declared-scope}). A handler that explicitly maps
 *       {@code OPTIONS} is governed by its own declaration
 *       ({@code options-on-guarded-route-with-no-header}).</li>
 *   <li>A CORS preflight is terminated by Spring's CORS handling, which this interceptor
 *       lets through without reading a header; no controller runs.</li>
 * </ul>
 *
 * <p>Error and async dispatches pass untouched: they continue a request that was already
 * decided on its way in, and re-deciding an error page's "route" would replace a 404 or 405
 * with a 500.
 */
public final class IntrospectionInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(IntrospectionInterceptor.class);

    private final AccessPolicy policy;

    public IntrospectionInterceptor(AccessPolicy policy) {
        this.policy = policy;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (request.getDispatcherType() == DispatcherType.ERROR || request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        String method = request.getMethod();

        return switch (Declarations.of(request, handler)) {
            case Declaration.CredentialsIgnored ignored -> {
                // The header is not read. A mutating method is refused here even though the
                // audit refuses to start such a mapping: a framework-owned handler can still
                // be mapped to every method.
                if (SafeMethods.isSafe(method)) {
                    yield true;
                }
                yield reject(response, Rejection.UNDECLARED_ROUTE);
            }
            case Declaration.Undeclared undeclared -> {
                log.error("Refusing {} {}: its handler {}", method, request.getRequestURI(), undeclared.reason());
                yield reject(response, Rejection.UNDECLARED_ROUTE);
            }
            case Declaration.Guarded guarded -> {
                String authorization = authorizationOf(request);
                yield switch (policy.decide(method, guarded.requirement(), authorization)) {
                    case Decision.Reject rejected -> reject(response, rejected.rejection());
                    case Decision.Proceed proceed -> {
                        if (proceed.identity() != null) {
                            publish(request, proceed.identity(), authorization);
                        }
                        yield true;
                    }
                };
            }
        };
    }

    /**
     * The {@code Authorization} header as one value, {@code null} when absent. Two header
     * lines are joined the way a proxy folds them — {@code "Bearer a, Bearer b"} — which is
     * four parts and therefore no credential; {@code getHeader} alone would silently pick
     * the first and let a caller choose which credential gets verified.
     */
    private static String authorizationOf(HttpServletRequest request) {
        List<String> lines = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        return lines.isEmpty() ? null : String.join(", ", lines);
    }

    /**
     * Hands the verified caller to the controller. The raw header is republished only here,
     * next to the verification that earned it, so an unverified credential is never relayed
     * to st-gateway. With a single header line — the only way to get here — the joined value
     * is that line, byte for byte.
     */
    private static void publish(HttpServletRequest request, Identity identity, String authorization) {
        request.setAttribute(CallerAttributes.SESSION_ATTRIBUTE,
                new Session(identity.sub(), identity.kind(), identity.scopes()));
        request.setAttribute(CallerAttributes.CALLER_AUTHORIZATION_ATTRIBUTE, authorization);
    }

    private static boolean reject(HttpServletResponse response, Rejection rejection) throws IOException {
        ErrorEnvelope.write(response, rejection);
        return false;
    }
}
