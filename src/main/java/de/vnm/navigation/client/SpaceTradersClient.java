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
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP client for the SpaceTraders v2 API, routed through st-gateway's shared
 * rate budget (meta#1/meta#7) rather than hitting SpaceTraders directly.
 *
 * <p>Requests carry no credential and no priority hint: st-gateway injects the agent
 * token (auth-design.md decision 5) and derives priority from the identity it verifies
 * itself (decision 2). This service holds nothing a caller could spoof.
 *
 * <p>Every failure leaves as an {@link ApiException}, and the status it carries is
 * st-gateway's own wherever the gateway answered at all — 4xx and 5xx alike, with the
 * gateway's message and its pacing headers. This service classifies exactly one
 * condition itself, "the gateway did not answer me" ({@code 504}), and keeps
 * {@code 502} for the narrow case of a response it cannot read. See meta's
 * {@code docs/design/upstream-errors.md}; the conformance cases are vendored into
 * {@code src/test/resources/gateway-errors.json}.
 *
 * <p>It used to collapse every upstream 5xx into {@code 502} and discard the body. The
 * gateway answers {@code 503 SpaceTraders credential not configured} when auth-service
 * holds no agent token — the one thing that says an operator must act rather than wait —
 * and that arrived here as a bare {@code 502} indistinguishable from a transient outage.
 * Nothing here is in a position to improve on the gateway's verdict: this service never
 * talked to SpaceTraders and cannot see whether a credential exists.
 *
 * <p>Nothing here returns {@code null} or an empty node to mean "something went wrong".
 */
@Component
public class SpaceTradersClient {

    private static final Logger log = LoggerFactory.getLogger(SpaceTradersClient.class);

    /** SpaceTraders caps {@code limit} at 20 waypoints per page. */
    private static final int PAGE_LIMIT = 20;

    /** Hard stop so a pager that never signals the end cannot spin forever. */
    private static final int MAX_PAGES = 500;

    /** How much of an unrecognised error body is worth relaying. */
    private static final int MAX_MESSAGE_LENGTH = 500;

    /**
     * How much of an error body is worth reading at all. Generous next to
     * {@link #MAX_MESSAGE_LENGTH} because a real envelope must parse whole, and small
     * enough that a broken intermediary answering with a stream cannot be bounded only
     * by the heap.
     */
    private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

    /**
     * Pacing signals st-gateway forwards on a passed-through 429, and that a caller needs
     * in order to back off rather than hammer the shared budget.
     */
    private static final List<String> FORWARDED_HEADERS =
            List.of("Retry-After", "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset");

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
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw relay(res, context);
                    })
                    .body(String.class);
        } catch (ApiException e) {
            throw e;
        } catch (RestClientException e) {
            // Connection refused, DNS failure, read timeout: st-gateway is not answering.
            // The one verdict this service is entitled to, because it is the only party
            // that observed it. 504 and not 502: the fault is upstream of the caller and
            // a retry may work, where a 502 here means "answered, and I cannot use it".
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT,
                    "st-gateway did not answer while fetching " + context + ": " + e.getMessage());
        }
    }

    /** Turns st-gateway's answer into this service's, changing as little as possible. */
    private ApiException relay(ClientHttpResponse res, String context) throws IOException {
        HttpStatusCode status = res.getStatusCode();
        String body = new String(res.getBody().readNBytes(MAX_ERROR_BODY_BYTES), StandardCharsets.UTF_8);
        String message = upstreamMessage(body);
        // The context lives in the log rather than in the message: what the caller needs
        // is the upstream's own sentence, unaltered, so that matching on it downstream
        // means the same thing whichever service relayed it.
        log.warn("st-gateway answered {} for {}: {}", status, context, message);

        Map<String, String> pacing = new LinkedHashMap<>();
        for (String name : FORWARDED_HEADERS) {
            String value = res.getHeaders().getFirst(name);
            if (value != null) pacing.put(name, value);
        }
        return new ApiException(status, message, pacing);
    }

    /**
     * The human-readable reason out of an error body.
     *
     * <p>st-gateway and SpaceTraders both answer {@code {"error":{"message"}}}. Anything
     * else — a proxy between here and the gateway answering with an HTML page — has no
     * message to lift, so the raw body stands in, truncated: an error message is read by
     * a person, and echoing a megabyte of someone else's markup through three service
     * logs is not diagnosis.
     */
    private String upstreamMessage(String body) {
        try {
            JsonNode message = objectMapper.readTree(body).path("error").path("message");
            if (message.isTextual() && !message.asText().isBlank()) return message.asText();
        } catch (Exception e) {
            // Not our envelope. Fall through to the raw body.
        }
        if (body.isBlank()) return "st-gateway returned an error with no message";
        return body.length() > MAX_MESSAGE_LENGTH ? body.substring(0, MAX_MESSAGE_LENGTH) : body;
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
