package de.vnm.navigation.introspection;

import java.util.Objects;
import java.util.Optional;

/**
 * The calling-service policy of token-introspection.md: which answer, for which declared
 * requirement, which {@code Authorization} header and which center response.
 *
 * <p>Framework-free on purpose. The Spring adapter hands it the method the request will be
 * handled as, the requirement the matched handler declared, and the raw header; the
 * conformance test drives exactly this through the adapter for all 37 fixture cases.
 */
public final class AccessPolicy {

    private final Introspector center;

    public AccessPolicy(Introspector center) {
        this.center = Objects.requireNonNull(center, "center");
    }

    /**
     * Decide one request. The order of the rules is the rule:
     *
     * <ol>
     *   <li>A mutating method on a route declaring {@code none} is a 500 — decided
     *       <b>before</b> the header is read, so a valid token, an inactive one and none at
     *       all get the same answer, and the center is not called.</li>
     *   <li>No bearer credential: a visitor on a route declaring {@code none} (only safe
     *       methods get here), otherwise a 401 without calling the center.</li>
     *   <li>Otherwise ask the center exactly once, and act on the answer.</li>
     * </ol>
     *
     * @param method        the method the request will be handled as, in any case
     * @param requirement   what the matched handler declared
     * @param authorization the {@code Authorization} header value, {@code null} when absent
     */
    public Decision decide(String method, Requirement requirement, String authorization) {
        Objects.requireNonNull(requirement, "requirement");
        if (requirement.isNone() && !SafeMethods.isSafe(method)) {
            // Default-deny. 500, not 403: our routing-table defect, which the caller can
            // never fix and automation-service would classify as a terminal credentials one.
            return new Decision.Reject(Rejection.UNDECLARED_ROUTE);
        }

        Optional<String> token = Bearer.tokenFrom(authorization);
        if (token.isEmpty()) {
            // A public read or a preflight with nothing presented: nothing to introspect,
            // so the center is not touched.
            return requirement.isNone()
                    ? new Decision.Proceed(null)
                    : new Decision.Reject(Rejection.MISSING_TOKEN);
        }

        return switch (center.introspect(token.get())) {
            // On every method, including a GET that would have been served to a visitor: a
            // bad credential is never downgraded to anonymous.
            case CenterAnswer.Inactive inactive -> new Decision.Reject(Rejection.INVALID_SESSION);
            // Never relay the center's own status. Its 401 is about our secret, not the caller.
            case CenterAnswer.Unavailable unavailable -> new Decision.Reject(Rejection.CENTER_UNAVAILABLE);
            case CenterAnswer.Active active -> requirement.admits(active.identity())
                    ? new Decision.Proceed(active.identity())
                    : new Decision.Reject(Rejection.MISSING_SCOPE);
        };
    }
}
