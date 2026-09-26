package de.vnm.navigation.introspection.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The handler serves visitors, and its answer may depend on an <i>optional</i> identity —
 * the family's {@code allowPublic()}, the fixture's {@code "none"}.
 *
 * <ul>
 *   <li>No {@code Authorization} header: the handler runs with no session and the center is
 *       not called.</li>
 *   <li>A bearer token: the center <b>is</b> asked, and the verified session reaches the
 *       handler. An inactive token is a 401 and a down center a 503, even here: a presented
 *       credential that does not verify is never downgraded to a visitor.</li>
 *   <li>A mutating method: 500 {@code this route declares no required scope}, before the
 *       header is read (default-deny). The startup audit refuses to start a handler
 *       carrying this annotation that is mapped to anything but {@code GET}, {@code HEAD}
 *       or {@code OPTIONS}.</li>
 * </ul>
 *
 * <p>For a route that never reads identity at all (health, API docs) use
 * {@link IgnoreCredentials} instead: this one verifies what it is shown.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AllowPublic {
}
