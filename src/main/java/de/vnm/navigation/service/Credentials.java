package de.vnm.navigation.service;

import de.vnm.navigation.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The one rule about the caller's SpaceTraders token: reads are public, live fetches
 * are not (auth-design.md decision 2/decision 18).
 *
 * <p>An anonymous caller gets the cache and nothing else. A cache miss for them is a
 * 401 raised here, not a 502 from calling st-gateway with a {@code Bearer null} header.
 */
final class Credentials {

    private Credentials() {}

    static void requireForLiveFetch(String token, String context) {
        if (token == null || token.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    "No cached data for " + context + " and no SpaceTraders credential to fetch it live");
        }
    }
}
