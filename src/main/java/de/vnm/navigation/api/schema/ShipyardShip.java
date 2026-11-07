package de.vnm.navigation.api.schema;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A ship on offer with its price.
 * Documentation only: no runtime code reads this type, and it may lag upstream SpaceTraders 2.3.0. */
public record ShipyardShip(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
        String name,
        String description,
        String supply,
        String activity,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer purchasePrice) {
}
