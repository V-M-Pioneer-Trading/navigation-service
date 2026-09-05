package de.vnm.navigation.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP client for the SpaceTraders v2 API, routed through st-gateway's shared
 * rate budget (meta#1/meta#7) rather than hitting SpaceTraders directly.
 *
 * <p>Requests carry no credential and no priority hint: st-gateway injects the agent
 * token (auth-design.md decision 5) and derives priority from the identity it verifies
 * itself (decision 2). This service holds nothing a caller could spoof.
 *
 * <p>Every failure mode leaves as an {@link ApiException} with a deliberate status:
 * an upstream 4xx keeps its status, an upstream 5xx and an unreachable gateway both
 * become {@code 502}, and so does any response this service cannot read. Nothing here
 * returns {@code null} or an empty node to mean "something went wrong".
 */
@Component
public class SpaceTradersClient {

    private static final Logger log = LoggerFactory.getLogger(SpaceTradersClient.class);

    /** SpaceTraders caps {@code limit} at 20 waypoints per page. */
    private static final int PAGE_LIMIT = 20;

    /** Hard stop so a pager that never signals the end cannot spin forever. */
    private static final int MAX_PAGES = 500;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public SpaceTradersClient(
            RestClient.Builder builder,
            ObjectMapper objectMapper,
            @Value("${spacetraders.base-url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
    }

    /**
     * Fetch a single waypoint.
     *
     * @param systemSymbol   e.g. {@code X1-FQ86}
     * @param waypointSymbol e.g. {@code X1-FQ86-B29}
     */
    public JsonNode fetchWaypoint(String systemSymbol, String waypointSymbol) {
        return fetchOne("/systems/{system}/waypoints/{waypoint}",
                "waypoint " + waypointSymbol, systemSymbol, waypointSymbol);
    }

    /** Fetch market data (imports/exports/prices) for a waypoint. */
    public JsonNode fetchMarket(String systemSymbol, String waypointSymbol) {
        return fetchOne("/systems/{system}/waypoints/{waypoint}/market",
                "market at " + waypointSymbol, systemSymbol, waypointSymbol);
    }

    /** Fetch shipyard data (ships for sale) for a waypoint. */
    public JsonNode fetchShipyard(String systemSymbol, String waypointSymbol) {
        return fetchOne("/systems/{system}/waypoints/{waypoint}/shipyard",
                "shipyard at " + waypointSymbol, systemSymbol, waypointSymbol);
    }

    /**
     * Fetch every waypoint in a system, following pagination.
     *
     * <p>{@code meta.total} is treated as advisory: responses that omit it used to end the
     * walk after a single page and silently return a truncated system. The end of the list
     * is a short page; {@code meta.total}, when present, only lets the walk stop one request
     * earlier.
     */
    public List<JsonNode> fetchWaypointsBySystem(String systemSymbol) {
        String context = "system " + systemSymbol;
        log.debug("Fetching all waypoints for {}", context);

        List<JsonNode> all = new ArrayList<>();
        int page = 1;
        boolean morePages;

        do {
            final int currentPage = page;
            String body = exchange(restClient.get()
                    .uri(u -> u.path("/systems/{system}/waypoints")
                               .queryParam("page", currentPage)
                               .queryParam("limit", PAGE_LIMIT)
                               .build(systemSymbol)),
                    context);

            JsonNode root = readTree(body, context);
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY,
                        "SpaceTraders response for " + context + " had no data array");
            }
            data.forEach(all::add);

            int total = root.path("meta").path("total").asInt(0);
            boolean shortPage = data.size() < PAGE_LIMIT;
            boolean haveAllPerMeta = total > 0 && all.size() >= total;
            morePages = !shortPage && !haveAllPerMeta && page < MAX_PAGES;
            page++;
        } while (morePages);

        if (page > MAX_PAGES) {
            log.warn("Stopped paging {} at the {}-page cap with {} waypoints collected",
                    context, MAX_PAGES, all.size());
        }
        log.debug("Fetched {} waypoints for {}", all.size(), context);
        return all;
    }

    // ── shared request plumbing ──────────────────────────────────────────────

    private JsonNode fetchOne(String path, String context, Object... uriVars) {
        log.debug("Fetching {} from SpaceTraders", context);
        String body = exchange(restClient.get().uri(path, uriVars), context);
        return requireData(body, context);
    }

    private String exchange(RestClient.RequestHeadersSpec<?> spec, String context) {
        try {
            return spec.retrieve()
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        HttpStatus status = HttpStatus.resolve(res.getStatusCode().value());
                        throw new ApiException(status != null ? status : HttpStatus.BAD_GATEWAY,
                                "SpaceTraders returned " + res.getStatusCode() + " for " + context);
                    })
                    .onStatus(HttpStatusCode::is5xxServerError, (req, res) -> {
                        throw new ApiException(HttpStatus.BAD_GATEWAY,
                                "SpaceTraders upstream error: " + res.getStatusCode() + " for " + context);
                    })
                    .body(String.class);
        } catch (ApiException e) {
            throw e;
        } catch (RestClientException e) {
            // Connection refused, DNS failure, read timeout: st-gateway is not answering.
            // Without this the request would surface as an undifferentiated 500.
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "SpaceTraders gateway unreachable while fetching " + context + ": " + e.getMessage());
        }
    }

    /** The {@code data} envelope every single-resource SpaceTraders response carries. */
    private JsonNode requireData(String body, String context) {
        JsonNode data = readTree(body, context).path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "SpaceTraders response for " + context + " carried no data payload");
        }
        return data;
    }

    private JsonNode readTree(String body, String context) {
        if (body == null || body.isBlank()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Empty response from SpaceTraders for " + context);
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Failed to parse SpaceTraders response for " + context + ": " + e.getMessage());
        }
    }
}
