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
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

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
 *       which takes the path's own intent: ignore credentials on a path whose handlers all
 *       ignore them (health, docs), otherwise {@code none} — a visitor proceeds, a bearer is
 *       still verified ({@code options-with-no-declared-scope}). A handler that explicitly maps
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
    private final Supplier<Collection<RequestMappingHandlerMapping>> mappings;

    /**
     * @param mappings the application's request mappings, read per {@code OPTIONS} request
     *                 answered by Spring's own responder, to give it the path's own intent
     */
    public IntrospectionInterceptor(AccessPolicy policy, Supplier<Collection<RequestMappingHandlerMapping>> mappings) {
        this.policy = policy;
        this.mappings = mappings;
    }

    /** Without request mappings: Spring's {@code OPTIONS} responder is then always {@code none}. */
    public IntrospectionInterceptor(AccessPolicy policy) {
        this(policy, List::of);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (request.getDispatcherType() == DispatcherType.ERROR || request.getDispatcherType() == DispatcherType.ASYNC) {
            return true;
        }
        String method = request.getMethod();

        return switch (Declarations.of(request, handler, mappings.get())) {
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
     * The {@code Authorization} header when the request carried exactly one such line;
     * otherwise {@code null}, which the policy reads as no credential. Two or more lines are
     * no credential whatever they hold: joining them the way a proxy folds them turned
     * {@code "Bearer a"} plus an empty second line into {@code "Bearer a, "}, which splits
     * into a credential {@code "a,"}. And {@code getHeader} alone would silently pick the
     * first line and let a caller choose which credential gets verified.
     */
    private static String authorizationOf(HttpServletRequest request) {
        List<String> lines = Collections.list(request.getHeaders(HttpHeaders.AUTHORIZATION));
        return lines.size() == 1 ? lines.get(0) : null;
    }

    /**
     * Hands the verified caller to the controller. The raw header is republished only here,
     * next to the verification that earned it, so an unverified credential is never relayed
     * to st-gateway. It is the single header line the request carried, byte for byte.
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
