package de.vnm.navigation.introspection.web;

import de.vnm.navigation.introspection.SafeMethods;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.ApplicationContext;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refuses to start the application while any request-mapped handler method lacks a
 * declaration — the Java counterpart of the family's {@code secured()} and agent-service's
 * {@code secureRouter}.
 *
 * <p>It walks the handler methods of every {@link RequestMappingHandlerMapping} — every
 * {@code @RequestMapping} in the application, ours and the libraries' — once all singletons
 * exist, and collects every problem before failing, so one startup names them all. It does
 * <b>not</b> see handlers of other types (resource handlers, CORS preflight, Spring's
 * built-in {@code OPTIONS} responder); those are resolved at request time by
 * {@link Declarations}, and any type it does not name is refused with a 500. The problems:
 *
 * <ul>
 *   <li>a handler method carrying no declaration, or more than one, or an unusable
 *       {@code @RequireScope} literal;</li>
 *   <li>a handler declaring {@link AllowPublic} or {@link IgnoreCredentials} that is mapped
 *       to a mutating method, or to every method (no method condition at all). Such a
 *       mapping would answer every mutation with the default-deny 500; refusing it here
 *       turns a latent outage into a failed deploy.</li>
 * </ul>
 *
 * <p>The request-time check in {@link IntrospectionInterceptor} stays in place regardless,
 * for handlers this walk cannot see: Spring's on-the-fly {@code OPTIONS} responder, static
 * resources, and anything registered after startup.
 */
public final class DeclarationAudit implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DeclarationAudit.class);

    private final ApplicationContext context;

    public DeclarationAudit(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void afterSingletonsInstantiated() {
        List<String> problems = new ArrayList<>();
        int audited = 0;
        for (RequestMappingHandlerMapping mapping : context.getBeansOfType(RequestMappingHandlerMapping.class).values()) {
            for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : mapping.getHandlerMethods().entrySet()) {
                audited++;
                String problem = problemWith(entry.getKey(), entry.getValue());
                if (problem != null) {
                    problems.add(problem);
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start: " + problems.size()
                    + " handler mapping(s) would be served without a credential declaration:\n  - "
                    + String.join("\n  - ", problems));
        }
        log.info("All {} handler mappings declare a credential requirement", audited);
    }

    /** One line naming the mapping and the handler, or {@code null} when it is fine. */
    static String problemWith(RequestMappingInfo info, HandlerMethod handler) {
        String where = info + " -> " + handler;
        Declaration declaration = Declarations.of(handler);
        if (declaration instanceof Declaration.Undeclared undeclared) {
            return where + " " + undeclared.reason();
        }
        boolean visitorsOnly = declaration instanceof Declaration.CredentialsIgnored
                || declaration instanceof Declaration.Guarded guarded && guarded.requirement().isNone();
        if (visitorsOnly && answersAMutatingMethod(info) && !Declarations.rendersErrors(handler)) {
            return where + " answers a mutating method but declares no session or scope";
        }
        log.debug("{} is declared {}", where, declaration);
        return null;
    }

    private static boolean answersAMutatingMethod(RequestMappingInfo info) {
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        return methods.isEmpty() || methods.stream().anyMatch(m -> !SafeMethods.isSafe(m.name()));
    }
}
