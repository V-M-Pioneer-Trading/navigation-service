package de.vnm.navigation.api.schema;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A waypoint's shipyard.
 * Documentation only: no runtime code reads this type, and it may lag upstream SpaceTraders 2.3.0. */
public record Shipyard(
        String symbol,
        List<ShipyardShipType> shipTypes,
        List<ShipyardShip> ships,
        Integer modificationsFee) {
}
