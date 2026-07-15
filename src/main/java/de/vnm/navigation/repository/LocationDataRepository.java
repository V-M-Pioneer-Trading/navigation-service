package de.vnm.navigation.repository;

import de.vnm.navigation.model.LocationDataEntity;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Shared JDBC access for symbol-keyed cached resources (market, shipyard) that share the
 * same {symbol, system_symbol, raw_json, fetched_at} shape as {@link WaypointRepository}'s
 * table, just without the coordinate/type columns. The table name is fixed per subclass,
 * never derived from request input.
 */
public abstract class LocationDataRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final String table;

    protected LocationDataRepository(NamedParameterJdbcTemplate jdbc, String table) {
        this.jdbc = jdbc;
        this.table = table;
    }

    public Optional<LocationDataEntity> findBySymbol(String symbol) {
        String sql = "SELECT symbol, system_symbol, raw_json, fetched_at FROM " + table +
                     " WHERE symbol = :symbol";
        List<LocationDataEntity> results = jdbc.query(sql,
                new MapSqlParameterSource("symbol", symbol), MAPPER);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /** Insert or replace a row (SQLite upsert). */
    public void upsert(LocationDataEntity entity) {
        String sql = "INSERT INTO " + table + " (symbol, system_symbol, raw_json, fetched_at) " +
                     "VALUES (:symbol, :systemSymbol, :rawJson, :fetchedAt) " +
                     "ON CONFLICT(symbol) DO UPDATE SET " +
                     "system_symbol = excluded.system_symbol, " +
                     "raw_json = excluded.raw_json, " +
                     "fetched_at = excluded.fetched_at";
        jdbc.update(sql, toParams(entity));
    }

    private MapSqlParameterSource toParams(LocationDataEntity e) {
        return new MapSqlParameterSource()
                .addValue("symbol", e.getSymbol())
                .addValue("systemSymbol", e.getSystemSymbol())
                .addValue("rawJson", e.getRawJson())
                .addValue("fetchedAt", e.getFetchedAt());
    }

    private static final RowMapper<LocationDataEntity> MAPPER = new RowMapper<>() {
        @Override
        public LocationDataEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new LocationDataEntity(
                    rs.getString("symbol"),
                    rs.getString("system_symbol"),
                    rs.getString("raw_json"),
                    rs.getString("fetched_at")
            );
        }
    };
}
