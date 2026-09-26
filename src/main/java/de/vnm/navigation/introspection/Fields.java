package de.vnm.navigation.introspection;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * Splitting a string into fields on runs of whitespace, empty pieces discarded — Go's
 * {@code strings.Fields} and {@code strings.FieldsFunc}, which is what agent-service uses for
 * both jobs below. Two definitions of whitespace, deliberately, one per job:
 *
 * <ul>
 *   <li>{@link #unicode} for the {@code Authorization} header, matching Go's
 *       {@code strings.Fields} ({@code unicode.IsSpace}) and TypeScript's {@code /\s+/}, so a
 *       header the rest of the family reads as three parts is three parts here too.</li>
 *   <li>{@link #ascii} for the center's {@code scope} string — space, tab, CR and LF —
 *       matching agent-service's {@code isScopeSeparator}: a scope carrying U+00A0 is one
 *       opaque scope, never two.</li>
 * </ul>
 *
 * <p>Neither is {@code String.split} with a limit, which is how a header like
 * {@code "Bearer abc def"} used to become the token {@code "abc def"}.
 */
final class Fields {

    private Fields() {}

    /** Fields separated by space, tab, CR or LF runs. */
    static List<String> ascii(String value) {
        return split(value, c -> c == ' ' || c == '\t' || c == '\r' || c == '\n');
    }

    /**
     * Fields separated by runs of Unicode {@code White_Space}, as Go's {@code unicode.IsSpace}
     * defines it: the ASCII controls tab, LF, VT, FF and CR, space, U+0085 (NEL), and every
     * Unicode space, line or paragraph separator (U+00A0, U+2000–U+200A, U+2028, U+3000, …).
     */
    static List<String> unicode(String value) {
        return split(value, c -> c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r'
                || c == 0x85 || Character.isSpaceChar(c));
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
