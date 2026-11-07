package de.vnm.navigation.api.schema;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A ship type the shipyard can build.
 * Documentation only: no runtime code reads this type, and it may lag upstream SpaceTraders 2.3.0. */
public record ShipyardShipType(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type) {
}
