package de.vnm.navigation.api.schema;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A SpaceTraders waypoint as served by the waypoint routes and inside the system listing.
 * Documentation only: no runtime code reads this type, and it may lag upstream SpaceTraders 2.3.0. */
public record Waypoint(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String symbol,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
        String systemSymbol,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer x,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer y,
        String orbits,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<WaypointTrait> traits,
        Boolean isUnderConstruction) {
}
