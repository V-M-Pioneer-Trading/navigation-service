package de.vnm.navigation.introspection.web;

import de.vnm.navigation.introspection.Requirement;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.web.servlet.error.BasicErrorController;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.resource.ResourceHttpRequestHandler;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the {@link Declaration} of whatever handler Spring MVC matched. The declaration
 * travels with the handler, so it is bound exactly where Spring's own matcher binds the
 * handler: a trailing slash, a case-variant path or a {@code {parameter}} can never become
 * a string this class looks up and misses.
 *
 * <p>Our own handlers declare with exactly one of the four annotations, on the handler
 * method. A handler Spring or a library contributed cannot be annotated, so the few this
 * service runs are declared here, by type, each for a stated reason:
 *
 * <table>
 *   <caption>Framework-owned handlers</caption>
 *   <tr><th>Handler</th><th>Declared</th><th>Why</th></tr>
 *   <tr><td>springdoc's {@code /api-docs} and Swagger UI controllers</td>
 *       <td>ignore credentials</td><td>documentation never reads identity</td></tr>
 *   <tr><td>{@link ResourceHttpRequestHandler} (Swagger UI's static files)</td>
 *       <td>ignore credentials</td><td>static files never read identity</td></tr>
 *   <tr><td>Spring Boot's own {@link BasicErrorController} (that class only, not any {@code ErrorController})</td>
 *       <td>ignore credentials</td><td>renders an error for a request already decided;
 *       reached directly, it answers safe methods only</td></tr>
 *   <tr><td>Spring MVC's built-in {@code OPTIONS} responder</td>
 *       <td>{@code none}</td><td>the fixture's {@code options-with-no-declared-scope}: it
 *       answers {@code Allow} for a path whose handlers declared nothing for
 *       {@code OPTIONS}, and runs no handler</td></tr>
 *   <tr><td>The CORS preflight handler</td><td>ignore credentials</td><td>the CORS layer
 *       terminating a preflight, which carries no {@code Authorization} header by
 *       definition; no controller runs</td></tr>
 * </table>
 *
 * <p>Anything else is {@link Declaration.Undeclared} — never {@code none}.
 */
public final class Declarations {

    /**
     * Spring MVC builds this handler on the fly for an {@code OPTIONS} request no mapping
     * declared, so it never appears in the handler mappings the audit walks. The class is
     * private to Spring, hence the name; if Spring renames it, the routing test for
     * {@code OPTIONS} fails with a 500 rather than anything quietly opening up.
     */
    private static final String SPRING_OPTIONS_RESPONDER =
            "org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping$HttpOptionsHandler";

    /**
     * The handler Spring MVC's CORS support substitutes for a preflight. Also private to
     * Spring and matched by name, for the same reason: any other non-method handler that
     * happens to see an {@code OPTIONS} with {@code Origin} is not the CORS layer and is
     * undeclared. If Spring renames it, the preflight routing test fails with a 500.
     */
    private static final String SPRING_PREFLIGHT_HANDLER =
            "org.springframework.web.servlet.handler.AbstractHandlerMapping$PreFlightHttpRequestHandler";

    private static final String SPRINGDOC_PACKAGE = "org.springdoc.";

    private static final Declaration IGNORED = new Declaration.CredentialsIgnored();

    private Declarations() {}

    /** The declaration of the handler Spring matched for {@code request}. */
    public static Declaration of(HttpServletRequest request, Object handler) {
        if (handler instanceof HandlerMethod method) {
            return of(method);
        }
        if (CorsUtils.isPreFlightRequest(request) && handler.getClass().getName().equals(SPRING_PREFLIGHT_HANDLER)) {
            return IGNORED;
        }
        if (handler instanceof ResourceHttpRequestHandler) {
            return IGNORED;
        }
        return new Declaration.Undeclared("a " + handler.getClass().getName() + " carries no declaration");
    }

    /** The declaration a handler method carries, or the framework-owned one for its type. */
    public static Declaration of(HandlerMethod handler) {
        Method method = handler.getMethod();
        List<Annotation> declared = declarationsOn(method);
        if (declared.size() > 1) {
            return new Declaration.Undeclared("declares " + declared.size()
                    + " requirements; exactly one of @AllowPublic, @RequireSession, @RequireScope or @IgnoreCredentials");
        }
        if (declared.size() == 1) {
            return fromAnnotation(declared.get(0));
        }
        return frameworkOwned(handler.getBeanType());
    }

    /**
     * Spring Boot's own error rendering answers whatever method failed, so it is mapped to
     * every method; the audit exempts it from the safe-methods-only rule for that reason
     * alone. Exactly {@link BasicErrorController}: an {@code ErrorController} anyone else
     * writes is an ordinary handler and declares like one.
     */
    static boolean rendersErrors(HandlerMethod handler) {
        return handler.getBeanType() == BasicErrorController.class;
    }

    private static List<Annotation> declarationsOn(Method method) {
        List<Annotation> found = new ArrayList<>(1);
        for (Class<? extends Annotation> type : List.of(
                AllowPublic.class, RequireSession.class, RequireScope.class, IgnoreCredentials.class)) {
            Annotation annotation = method.getAnnotation(type);
            if (annotation != null) {
                found.add(annotation);
            }
        }
        return found;
    }

    private static Declaration fromAnnotation(Annotation annotation) {
        return switch (annotation) {
            case AllowPublic ignored -> new Declaration.Guarded(Requirement.none());
            case RequireSession ignored -> new Declaration.Guarded(Requirement.session());
            case IgnoreCredentials ignored -> IGNORED;
            case RequireScope scope -> {
                try {
                    yield new Declaration.Guarded(Requirement.scope(scope.value()));
                } catch (IllegalArgumentException invalid) {
                    yield new Declaration.Undeclared("@RequireScope: " + invalid.getMessage());
                }
            }
            default -> throw new IllegalStateException("not a declaration: " + annotation);
        };
    }

    private static Declaration frameworkOwned(Class<?> beanType) {
        if (beanType.getName().startsWith(SPRINGDOC_PACKAGE)) {
            return IGNORED;
        }
        if (beanType == BasicErrorController.class) {
            return IGNORED;
        }
        if (beanType.getName().equals(SPRING_OPTIONS_RESPONDER)) {
            return new Declaration.Guarded(Requirement.none());
        }
        return new Declaration.Undeclared(
                "carries none of @AllowPublic, @RequireSession, @RequireScope or @IgnoreCredentials");
    }
}
