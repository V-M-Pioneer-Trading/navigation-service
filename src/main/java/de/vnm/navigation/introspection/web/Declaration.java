package de.vnm.navigation.introspection.web;

import de.vnm.navigation.introspection.Requirement;

/**
 * What a matched handler declared about credentials, as {@link Declarations} resolved it.
 *
 * <p>Three outcomes, and the third is the reason this type exists: <i>undeclared</i> is not
 * {@code none}. {@code none} is a handler that said, out loud, that visitors may use it;
 * undeclared is a handler nobody said anything about, and it is never served, on any
 * method.
 */
public sealed interface Declaration {

    /** {@link AllowPublic}, {@link RequireSession} or {@link RequireScope}: the policy applies. */
    record Guarded(Requirement requirement) implements Declaration {}

    /**
     * {@link IgnoreCredentials}, or a framework-owned handler that never reads identity:
     * the header is not read and the center is not called. Safe methods only.
     */
    record CredentialsIgnored() implements Declaration {}

    /** No usable declaration. The reason names what is missing or wrong, for logs and the audit. */
    record Undeclared(String reason) implements Declaration {}
}
