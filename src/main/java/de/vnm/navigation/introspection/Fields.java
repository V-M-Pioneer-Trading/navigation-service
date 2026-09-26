package de.vnm.navigation.introspection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Splitting a string into fields on runs of separators, empty pieces discarded — Go's
 * {@code strings.Fields} and {@code strings.FieldsFunc}, which is what agent-service uses
 * for both jobs below. One separator set per job:
 *
 * <ul>
 *   <li>{@link #whitespace} for the {@code Authorization} header: tab, LF, VT, FF, CR and
 *       space. {@link Bearer} refuses any non-ASCII header before splitting, and over ASCII
 *       these six are exactly what Go's {@code unicode.IsSpace} (and so
 *       {@code strings.Fields}) and JavaScript's {@code \s} treat as whitespace.</li>
 *   <li>{@link #scopeSeparators} for the center's {@code scope} string — space, tab, CR and
 *       LF — matching agent-service's {@code isScopeSeparator}: a scope carrying U+00A0 is
 *       one opaque scope, never two.</li>
 * </ul>
 *
 * <p>Neither is {@code String.split} with a limit, which is how a header like
 * {@code "Bearer abc def"} used to become the token {@code "abc def"}.
 */
final class Fields {

    private Fields() {}

    /** Fields separated by runs of ASCII whitespace: tab, LF, VT, FF, CR, space. */
    static List<String> whitespace(String value) {
        return split(value, c -> c == ' ' || (c >= '\t' && c <= '\r'));
    }

    /** Fields separated by runs of space, tab, CR or LF. */
    static List<String> scopeSeparators(String value) {
        return split(value, c -> c == ' ' || c == '\t' || c == '\r' || c == '\n');
    }

    private static List<String> split(String value, IntPredicate isSeparator) {
        List<String> parts = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < value.length(); i++) {
            if (isSeparator.test(value.charAt(i))) {
                if (start >= 0) {
                    parts.add(value.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        if (start >= 0) {
            parts.add(value.substring(start));
        }
        return parts;
    }
}
