package de.vnm.navigation.introspection.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The handler never reads identity — the family's {@code ignoreCredentials()}. The
 * {@code Authorization} header is not read, the center is never called, and a bearer sent
 * here, valid, garbage or none, changes nothing. That is what keeps the health probe
 * answering while auth-service is down.
 *
 * <p>For health and API documentation only. A mutating method is refused with a 500 at
 * request time, and the startup audit refuses to start a handler carrying this annotation
 * that is mapped to anything but {@code GET}, {@code HEAD} or {@code OPTIONS}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface IgnoreCredentials {
}
