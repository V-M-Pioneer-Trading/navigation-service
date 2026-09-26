package de.vnm.navigation.introspection;

/** What {@link AccessPolicy} decided for one inbound request. */
public sealed interface Decision {

    /**
     * The handler may run.
     *
     * @param identity the verified caller, or {@code null} for a visitor who presented no
     *                 credential on a route declaring {@code none}
     */
    record Proceed(Identity identity) implements Decision {}

    /** The request is refused before any handler runs. */
    record Reject(Rejection rejection) implements Decision {}
}
