package de.vnm.navigation.api.schema;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The system listing: every waypoint of a system and their count.
 * Documentation only: no runtime code reads this type, and it may lag upstream SpaceTraders 2.3.0. */
public record SystemWaypointsResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Waypoint> data,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer total) {
}
