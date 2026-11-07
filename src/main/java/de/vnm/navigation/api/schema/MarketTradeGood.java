package de.vnm.navigation.api.schema;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A good with its current price at a market.
 * Documentation only: no runtime code reads this type, and it may lag upstream SpaceTraders 2.3.0. */
public record MarketTradeGood(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String symbol,
        String type,
        Integer tradeVolume,
        String supply,
        String activity,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer purchasePrice,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Integer sellPrice) {
}
