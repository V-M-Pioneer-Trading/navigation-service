package de.vnm.navigation.service;

import de.vnm.navigation.auth.Session;
import de.vnm.navigation.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The one rule about who may cause an upstream call: reads are public, live fetches
 * are not (auth-design.md decisions 2 and 3).
 *
 * <p>An anonymous caller gets the cache and nothing else. A cache miss for them is a
 * 401 raised here rather than a fetch on the fleet's credential — since st-gateway
 * injects the agent token on every call (decision 5), this check is what stops an
 * anonymous visitor from walking the universe on the fleet's rate budget.
 */
final class LiveFetch {

    private LiveFetch() {}

    static void requireSession(Session session, String context) {
        if (session == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    "No cached data for " + context + "; sign in to fetch it live");
        }
    }
}
