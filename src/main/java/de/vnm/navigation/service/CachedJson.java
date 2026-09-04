package de.vnm.navigation.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.time.Instant;

/**
 * Reads and writes the raw SpaceTraders JSON blobs the cache tables store, plus the
 * timestamps that go with them. A blob that will not parse is this service's fault,
 * not the upstream's, so it surfaces as a 500 rather than a 502.
 */
final class CachedJson {

    private final ObjectMapper objectMapper;

    CachedJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    JsonNode read(String rawJson, String context) {
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Corrupt cached data for " + context + ": " + e.getMessage());
        }
    }

    String write(JsonNode node, String context) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to serialize JSON for " + context + ": " + e.getMessage());
        }
    }

    /**
     * Parse a stored {@code fetched_at}, or {@code null} if it is unreadable.
     *
     * <p>A row written by an older build or edited by hand must not be able to wedge a
     * cache lookup: an unreadable timestamp reads as "age unknown", which every caller
     * treats as stale.
     */
    static Instant fetchedAtOrNull(String fetchedAt) {
        try {
            return Instant.parse(fetchedAt);
        } catch (Exception e) {
            return null;
        }
    }
}
