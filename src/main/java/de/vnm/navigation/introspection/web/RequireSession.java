package de.vnm.navigation.introspection.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The handler runs for any verified session, with or without scopes — the family's
 * {@code requireSession()}, the fixture's {@code "session"} tier.
 *
 * <p>A session carrying no scopes at all is let through (a guest who may watch but not
 * act); no header is a 401, and an inactive token is a 401. Nothing in navigation-service
 * declares it today; it exists so the service can say "signed in, nothing more" without
 * inventing a scope for it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequireSession {
}
