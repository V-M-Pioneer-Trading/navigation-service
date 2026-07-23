package de.vnm.navigation.api;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bare, unauthenticated liveness probe — not versioned under /api/navigation/v1.
 * Mounted both bare (local dev/compose) and under /api/navigation (production
 * CloudFront only routes requests matching a configured path pattern).
 */
@RestController
public class HealthController {

    @GetMapping({"/health", "/api/navigation/health"})
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
