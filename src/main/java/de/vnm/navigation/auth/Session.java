package de.vnm.navigation.auth;

import java.util.Set;

/**
 * A verified Clerk identity: who the caller is and what they are allowed to do.
 *
 * <p>The only thing the rest of this service learns about a caller. A {@code null}
 * {@code Session} means "anonymous" — a request that carried no {@code Authorization}
 * header at all. A request that carried one that did not verify never reaches a
 * controller (see {@link ClerkAuthFilter}).
 *
 * @param subject the token's {@code sub}: a Clerk user id for a human operator, or a
 *                machine id ({@code mch_…}) for an M2M caller
 * @param scopes  the space-delimited {@code scope} claim, split
 */
public record Session(String subject, Set<String> scopes) {

    public Session {
        scopes = Set.copyOf(scopes);
    }

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
