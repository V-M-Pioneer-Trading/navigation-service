package de.vnm.navigation.introspection;

/**
 * What one introspection call learned. Three outcomes and no more.
 *
 * <p>{@link Unavailable} deliberately carries no detail. It covers a center that was
 * unreachable, timed out, answered non-2xx (its 401 about OUR caller secret included),
 * redirected, or answered a body that is not the contract. The caller's remedy is the same
 * in all of them, and a client that could tell them apart would only be tempted to relay
 * the difference.
 */
public sealed interface CenterAnswer {

    CenterAnswer INACTIVE = new Inactive();
    CenterAnswer UNAVAILABLE = new Unavailable();

    /** {@code active: true}, with the identity the center vouched for. */
    record Active(Identity identity) implements CenterAnswer {}

    /** {@code active: false}: invalid, expired, foreign-signed or malformed token. */
    record Inactive() implements CenterAnswer {}

    /** The center could not be asked, or its answer could not be understood. */
    record Unavailable() implements CenterAnswer {}
}
