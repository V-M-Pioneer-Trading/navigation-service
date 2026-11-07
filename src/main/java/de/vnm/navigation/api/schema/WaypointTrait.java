package de.vnm.navigation.api.schema;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A trait of a waypoint.
 * Documentation only: no runtime code reads this type, and it may lag upstream SpaceTraders 2.3.0. */
public record WaypointTrait(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String symbol,
        String name,
        String description) {
}
