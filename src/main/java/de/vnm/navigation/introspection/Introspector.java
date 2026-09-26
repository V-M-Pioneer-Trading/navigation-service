package de.vnm.navigation.introspection;

/**
 * Asks the center about one token. {@link IntrospectionClient} is the only production
 * implementation; the seam exists so the rules can be read without HTTP in the way.
 */
@FunctionalInterface
public interface Introspector {

    /**
     * Never throws and never returns {@code null}: every way of failing is
     * {@link CenterAnswer#UNAVAILABLE}.
     */
    CenterAnswer introspect(String token);
}
