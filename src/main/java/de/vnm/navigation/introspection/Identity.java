package de.vnm.navigation.introspection;

import java.util.List;
import java.util.Objects;

/**
 * What a verified token carries, exactly as the center reported it.
 *
 * @param sub    the token's subject; never empty
 * @param kind   the center's classification of the subject ({@code operator} or
 *               {@code machine}), used verbatim. Never re-derived from the {@code sub}
 *               prefix: the center is the one place in the fleet that knows Clerk's subject
 *               conventions, and the fixture's {@code kind-disagrees-with-sub-prefix} case
 *               exists to catch a client that looks anyway.
 * @param scopes the center's {@code scope} string split on ASCII whitespace runs, empties
 *               discarded, in the order the center sent them. Empty, never {@code null},
 *               when the session carries none — including when the key was absent.
 */
public record Identity(String sub, String kind, List<String> scopes) {

    public Identity {
        Objects.requireNonNull(sub, "sub");
        Objects.requireNonNull(kind, "kind");
        scopes = List.copyOf(scopes);
    }

    /**
     * Exact membership of the split list: no prefix, no namespace walk, no substring, no
     * case-folding. {@code fleet:control:read} does not satisfy {@code fleet:control}, and
     * neither does {@code FLEET:CONTROL}.
     */
    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
