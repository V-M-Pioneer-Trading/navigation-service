package de.vnm.navigation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.vnm.navigation.introspection.TestCenter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fails when a waypoint, system-waypoints, market or shipyard route declares a free-form
 * success body (meta#172). Reads {@code /api-docs} live from the whole application context, not
 * the committed {@code openapi.json}, so a stale file cannot mask a regression and
 * {@code ./gradlew test} runs it.
 *
 * <p>Every schema reachable from a route's 200 body must be an object with properties that
 * stays open ({@code additionalProperties: true}: the service passes upstream bodies through
 * unvalidated, so a model must not claim to be exhaustive), and its {@code required} set is
 * compared exactly: a required field the service does not always send makes a consumer's
 * generated validator reject a real answer.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiBodiesTest {

    private static final Path DATABASE = temporaryDatabase();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE);
        TestCenter.register(registry);
    }

    /** "method path" to the body model, and to the dotted paths that must be typed leaves. */
    private static final Map<String, String> ROUTE_MODEL = new LinkedHashMap<>();
    private static final Map<String, List<String>> LEAVES = new LinkedHashMap<>();

    private static final List<String> WAYPOINT_LEAVES = List.of(
            "symbol", "type", "x", "y", "orbits", "traits[].symbol", "isUnderConstruction");
    private static final List<String> LIST_LEAVES = List.of(
            "data[].symbol", "data[].type", "data[].x", "data[].y", "data[].orbits",
            "data[].traits[].symbol", "data[].isUnderConstruction", "total");
    private static final List<String> MARKET_LEAVES = List.of(
            "symbol", "tradeGoods[].symbol", "tradeGoods[].purchasePrice", "tradeGoods[].sellPrice");
    private static final List<String> SHIPYARD_LEAVES = List.of(
            "shipTypes[].type", "ships[].type", "ships[].purchasePrice");

    static {
        route("get /api/navigation/v1/waypoints/{symbol}", "Waypoint", WAYPOINT_LEAVES);
        route("post /api/navigation/v1/waypoints/{symbol}/refresh", "Waypoint", WAYPOINT_LEAVES);
        route("get /api/navigation/v1/systems/{systemSymbol}/waypoints", "SystemWaypointsResponse", LIST_LEAVES);
        route("post /api/navigation/v1/systems/{systemSymbol}/waypoints/refresh", "SystemWaypointsResponse", LIST_LEAVES);
        route("get /api/navigation/v1/waypoints/{symbol}/market", "Market", MARKET_LEAVES);
        route("post /api/navigation/v1/waypoints/{symbol}/market/refresh", "Market", MARKET_LEAVES);
        route("get /api/navigation/v1/waypoints/{symbol}/shipyard", "Shipyard", SHIPYARD_LEAVES);
        route("post /api/navigation/v1/waypoints/{symbol}/shipyard/refresh", "Shipyard", SHIPYARD_LEAVES);
    }

    private static void route(String key, String model, List<String> leaves) {
        ROUTE_MODEL.put(key, model);
        LEAVES.put(key, leaves);
    }

    /** Required only where upstream 2.3 requires it and a consumer types it as required. */
    private static final Map<String, Set<String>> REQUIRED = Map.of(
            "Waypoint", Set.of("symbol", "type", "x", "y", "traits"),
            "WaypointTrait", Set.of("symbol"),
            "SystemWaypointsResponse", Set.of("data", "total"),
            "Market", Set.of("symbol"),
            "TradeGood", Set.of(),
            "MarketTradeGood", Set.of("symbol", "purchasePrice", "sellPrice"),
            "Shipyard", Set.of(),
            "ShipyardShipType", Set.of("type"),
            "ShipyardShip", Set.of("type", "purchasePrice"));

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private JsonNode spec() throws Exception {
        String served = mvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return mapper.readTree(served);
    }

    @Test
    void everyNavigationOperationIsCoveredByTheList() throws Exception {
        Set<String> served = new TreeSet<>();
        spec().path("paths").fields().forEachRemaining(path -> {
            if (!path.getKey().startsWith("/api/navigation/v1")) {
                return;
            }
            path.getValue().fieldNames().forEachRemaining(
                    method -> served.add(method + " " + path.getKey()));
        });

        assertThat(served).containsExactlyInAnyOrderElementsOf(ROUTE_MODEL.keySet());
    }

    @Test
    void successBodyIsTypedApplicationJson() throws Exception {
        JsonNode spec = spec();
        for (Map.Entry<String, String> route : ROUTE_MODEL.entrySet()) {
            String[] parts = route.getKey().split(" ");
            JsonNode content = spec.path("paths").path(parts[1]).path(parts[0])
                    .path("responses").path("200").path("content");

            assertThat(fieldNames(content)).as("%s media types", route.getKey())
                    .containsExactly("application/json");
            JsonNode schema = content.path("application/json").path("schema");
            assertThat(schema.path("$ref").asText()).as("%s schema", route.getKey())
                    .isEqualTo("#/components/schemas/" + route.getValue());
        }
    }

    @Test
    void everyReachableSchemaIsAnOpenObjectWithProperties() throws Exception {
        JsonNode spec = spec();
        Set<String> seen = new LinkedHashSet<>();
        for (String model : new HashSet<>(ROUTE_MODEL.values())) {
            walk(spec, "#/components/schemas/" + model, seen);
        }

        assertThat(seen).as("models reachable from the 8 routes")
                .extracting(ref -> ref.substring(ref.lastIndexOf('/') + 1))
                .containsExactlyInAnyOrderElementsOf(REQUIRED.keySet());
        for (String ref : seen) {
            String name = ref.substring(ref.lastIndexOf('/') + 1);
            JsonNode schema = resolve(spec, ref);
            assertThat(schema.path("additionalProperties").asText())
                    .as("%s additionalProperties", name).isEqualTo("true");
            assertThat(valuesOf(schema.path("required")))
                    .as("%s required", name).isEqualTo(REQUIRED.get(name));
        }
    }

    @Test
    void minimumFieldsResolveToTypedLeaves() throws Exception {
        JsonNode spec = spec();
        for (Map.Entry<String, List<String>> route : LEAVES.entrySet()) {
            String root = "#/components/schemas/" + ROUTE_MODEL.get(route.getKey());
            for (String path : route.getValue()) {
                JsonNode node = resolve(spec, root);
                for (String step : path.split("\\.")) {
                    boolean array = step.endsWith("[]");
                    String field = array ? step.substring(0, step.length() - 2) : step;
                    node = deref(spec, node.path("properties").path(field));
                    if (array) {
                        assertThat(node.path("type").asText()).as("%s %s", route.getKey(), path).isEqualTo("array");
                        node = deref(spec, node.path("items"));
                    }
                }
                assertThat(node.path("type").asText()).as("%s %s", route.getKey(), path)
                        .isIn("string", "integer", "boolean");
            }
        }
    }

    /** Follows $ref, items and properties; fails on JsonNode and on anything free-form. */
    private void walk(JsonNode spec, String ref, Set<String> seen) {
        assertThat(ref).as("free-form JsonNode reachable").doesNotEndWith("/JsonNode");
        if (!seen.add(ref)) {
            return;
        }
        JsonNode schema = resolve(spec, ref);
        assertThat(schema.path("type").asText()).as("%s type", ref).isEqualTo("object");
        assertThat(schema.path("properties").size()).as("%s is free-form (no properties)", ref).isPositive();
        for (String composition : List.of("allOf", "oneOf", "anyOf")) {
            assertThat(schema.has(composition)).as("%s uses %s", ref, composition).isFalse();
        }
        schema.path("properties").fields().forEachRemaining(property -> visit(spec, property.getValue(), seen));
    }

    private void visit(JsonNode spec, JsonNode node, Set<String> seen) {
        if (node.has("$ref")) {
            walk(spec, node.get("$ref").asText(), seen);
        } else if (node.has("items")) {
            visit(spec, node.get("items"), seen);
        } else {
            assertThat(node.path("type").asText()).as("member %s", node)
                    .isIn("string", "integer", "boolean", "number");
        }
    }

    private static JsonNode resolve(JsonNode spec, String ref) {
        JsonNode node = spec.at(ref.substring(1));
        assertThat(node.isMissingNode()).as("%s resolves", ref).isFalse();
        return node;
    }

    private static JsonNode deref(JsonNode spec, JsonNode node) {
        return node.has("$ref") ? resolve(spec, node.get("$ref").asText()) : node;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static Set<String> valuesOf(JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(v -> values.add(v.asText()));
        return values;
    }

    private static Path temporaryDatabase() {
        try {
            Path directory = Files.createTempDirectory("navigation-service-bodies");
            directory.toFile().deleteOnExit();
            Path database = directory.resolve("nav.db");
            database.toFile().deleteOnExit();
            return database;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
