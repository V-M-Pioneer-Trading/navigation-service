package de.vnm.navigation.introspection.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The handler runs only for a verified session carrying exactly this scope literal — the
 * family's {@code requireScope(scope)}.
 *
 * <p>No header is a 401 {@code a bearer token is required}; an inactive token a 401
 * {@code invalid or expired session}; a valid session without the literal a 403 that names
 * no scope. Matching is exact membership: no prefix, no namespace, no case-folding, and no
 * scope implies another ({@code fleet:control} does not imply {@code universe:refresh},
 * auth-design.md decision 20). Enforced on every method the handler is mapped to, the safe
 * ones included, so {@code HEAD} of a scoped {@code GET} is the same 401 as the {@code GET}.
 *
 * <p>A blank literal, one containing whitespace, or one of the reserved words
 * {@code none}, {@code session} and {@code ignore-credentials} refuses to start.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireScope {

    /** The scope literal, exactly as the center returns it in {@code scope}. */
    String value();
}
