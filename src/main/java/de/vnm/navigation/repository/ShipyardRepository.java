package de.vnm.navigation.repository;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ShipyardRepository extends LocationDataRepository {
    public ShipyardRepository(NamedParameterJdbcTemplate jdbc) {
        super(jdbc, "shipyards");
    }
}
