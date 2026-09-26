package de.vnm.navigation.auth;

import java.util.List;
import java.util.Objects;

/**
 * A verified caller: who they are, what kind of caller the center says they are, and what
 * they may do.
 *
 * <p>The only thing the rest of this service learns about a caller. It is published as the
 * {@link CallerAttributes#SESSION_ATTRIBUTE} request attribute by the introspection
 * interceptor, and only after auth-service vouched for the token (auth-design.md
 * decision 21). A {@code null} {@code Session} means "anonymous" — a request that carried no
 * {@code Authorization} header at all on a public read. A request that carried one that did
 * not verify never reaches a controller.
 *
 * @param subject the token's {@code sub}: a Clerk user id for a human operator, or a machine
 *                id ({@code mch_…}) for an M2M caller
 * @param kind    the center's {@code kind} — {@code operator} or {@code machine} — used
 *                verbatim; never re-derive it from the subject's prefix
 * @param scopes  the center's space-delimited {@code scope}, split, in the order it was sent
 */
public record Session(String subject, String kind, List<String> scopes) {

    public Session {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(kind, "kind");
        scopes = List.copyOf(scopes);
    }

    /** Exact membership: no prefix, no case-folding, and no scope implies another. */
    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
