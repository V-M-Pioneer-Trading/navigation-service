package de.vnm.navigation.api.schema;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** A waypoint's market.
 * Documentation only: no runtime code reads this type, and it may lag upstream SpaceTraders 2.3.0. */
public record Market(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String symbol,
        List<TradeGood> exports,
        List<TradeGood> imports,
        List<TradeGood> exchange,
        List<MarketTradeGood> tradeGoods) {
}
