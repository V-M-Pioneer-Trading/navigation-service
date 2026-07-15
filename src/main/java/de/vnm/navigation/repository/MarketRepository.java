package de.vnm.navigation.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MarketRepository extends LocationDataRepository {
    public MarketRepository(NamedParameterJdbcTemplate jdbc) {
        super(jdbc, "markets");
    }
}
