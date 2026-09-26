package de.vnm.navigation.introspection;

import java.util.Locale;
import java.util.Set;

/**
 * RFC 9110 §9.2.1's safe methods — {@code GET}, {@code HEAD} and {@code OPTIONS} —
 * compared case-insensitively, as the fixture's {@code lowercase-route-method} case pins.
 *
 * <p>They are exempt from <b>default-deny only</b>: a safe method on a route declaring
 * {@code none} proceeds as a visitor instead of answering 500. A safe method on a route that
 * declared a session or a scope is enforced exactly as a {@code POST} is.
 */
public final class SafeMethods {

    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    private SafeMethods() {}

    /** {@code true} for GET, HEAD and OPTIONS in any case; {@code false} for anything else, null included. */
    public static boolean isSafe(String method) {
        return method != null && SAFE.contains(method.toUpperCase(Locale.ROOT));
    }
}
