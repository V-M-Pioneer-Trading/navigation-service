package de.vnm.navigation.introspection;

import java.util.ArrayList;
import java.util.List;

/**
 * Splitting on runs of ASCII whitespace — space, tab, CR and LF — with empty pieces
 * discarded. The one splitter for both the {@code Authorization} header and the center's
 * {@code scope} string, matching agent-service's {@code isScopeSeparator}.
 *
 * <p>Unicode spaces are deliberately <i>not</i> separators: a scope carrying U+00A0 is one
 * opaque scope, never two. And this is not {@code String.split} with a limit, which is how a
 * header like {@code "Bearer abc def"} used to become the token {@code "abc def"}.
 */
final class AsciiWhitespace {

    private AsciiWhitespace() {}

    static List<String> split(String value) {
        List<String> parts = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < value.length(); i++) {
            if (isSeparator(value.charAt(i))) {
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

    private static boolean isSeparator(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }
}
