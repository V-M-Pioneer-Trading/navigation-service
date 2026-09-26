package de.vnm.navigation.introspection;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What a route declared it needs, in the fixture's three tiers: {@code none},
 * {@code session} or one scope literal.
 *
 * <p>There is deliberately no fourth value for "undeclared". {@code none} is a route that
 * <i>said</i> no credential is needed; a route nobody declared anything for is a
 * routing-table defect, and it is the adapter's job to refuse it loudly rather than to
 * build a {@code Requirement} for it. A resolver miss is never {@code none}.
 */
public final class Requirement {

    /**
     * Spellings that would read as a demand and silently produce their opposite if accepted
     * as scope literals: the fixture's words for the other tiers, and the family's word for
     * a route that ignores credentials. The TypeScript client refuses the same three.
     */
    private static final Set<String> RESERVED = Set.of("none", "session", "ignore-credentials");

    private static final Requirement NONE = new Requirement(Tier.NONE, null);
    private static final Requirement SESSION = new Requirement(Tier.SESSION, null);

    private enum Tier { NONE, SESSION, SCOPE }

    private final Tier tier;
    private final String scope;

    private Requirement(Tier tier, String scope) {
        this.tier = tier;
        this.scope = scope;
    }

    /**
     * The route needs no credential. On a mutating method that is refused with a 500
     * (default-deny); on GET, HEAD and OPTIONS a caller without a header proceeds as a
     * visitor, and a caller <i>with</i> one is still verified.
     */
    public static Requirement none() {
        return NONE;
    }

    /** Any verified session will do, with or without scopes. */
    public static Requirement session() {
        return SESSION;
    }

    /**
     * The session must carry exactly this scope literal.
     *
     * @throws IllegalArgumentException for a blank literal, one containing whitespace (the
     *                                  center's list is split on it, so no token could ever
     *                                  carry it), or a reserved word
     */
    public static Requirement scope(String literal) {
        Objects.requireNonNull(literal, "scope literal");
        if (!Fields.scopeSeparators(literal).equals(List.of(literal))) {
            throw new IllegalArgumentException(
                    "a required scope must be one non-empty literal without whitespace, not \"" + literal + "\"");
        }
        if (RESERVED.contains(literal)) {
            throw new IllegalArgumentException(
                    "\"" + literal + "\" is not a scope; it is the name of another declaration");
        }
        return new Requirement(Tier.SCOPE, literal);
    }

    /** {@code true} when the route declared that it needs no credential. */
    public boolean isNone() {
        return tier == Tier.NONE;
    }

    /**
     * Whether a verified caller satisfies this requirement: always for {@code none} and
     * {@code session}, and by exact membership for a scope.
     */
    boolean admits(Identity identity) {
        return tier != Tier.SCOPE || identity.hasScope(scope);
    }

    /** The fixture's spelling: {@code none}, {@code session} or the scope literal. */
    @Override
    public String toString() {
        return switch (tier) {
            case NONE -> "none";
            case SESSION -> "session";
            case SCOPE -> scope;
        };
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Requirement that && tier == that.tier && Objects.equals(scope, that.scope);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tier, scope);
    }
}
