package de.vnm.navigation.repository;

import de.vnm.navigation.model.WaypointEntity;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

@Repository
public class WaypointRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public WaypointRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<WaypointEntity> findBySymbol(String symbol) {
        String sql = "SELECT symbol, system_symbol, type, x, y, raw_json, fetched_at " +
                     "FROM waypoints WHERE symbol = :symbol";
        List<WaypointEntity> results = jdbc.query(sql,
                new MapSqlParameterSource("symbol", symbol), MAPPER);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<WaypointEntity> findBySystemSymbol(String systemSymbol) {
        String sql = "SELECT symbol, system_symbol, type, x, y, raw_json, fetched_at " +
                     "FROM waypoints WHERE system_symbol = :systemSymbol";
        return jdbc.query(sql, new MapSqlParameterSource("systemSymbol", systemSymbol), MAPPER);
    }

    /** Insert or replace a waypoint row (SQLite upsert). */
    public void upsert(WaypointEntity entity) {
        String sql = """
                INSERT INTO waypoints (symbol, system_symbol, type, x, y, raw_json, fetched_at)
                VALUES (:symbol, :systemSymbol, :type, :x, :y, :rawJson, :fetchedAt)
                ON CONFLICT(symbol) DO UPDATE SET
                    system_symbol = excluded.system_symbol,
                    type          = excluded.type,
                    x             = excluded.x,
                    y             = excluded.y,
                    raw_json      = excluded.raw_json,
                    fetched_at    = excluded.fetched_at
                """;
        jdbc.update(sql, toParams(entity));
    }

    /** Delete all waypoints for a system (used before a full system refresh). */
    public int deleteBySystemSymbol(String systemSymbol) {
        return jdbc.update("DELETE FROM waypoints WHERE system_symbol = :systemSymbol",
                new MapSqlParameterSource("systemSymbol", systemSymbol));
    }

    /** Delete a single waypoint (used before a single-waypoint refresh). */
    public int deleteBySymbol(String symbol) {
        return jdbc.update("DELETE FROM waypoints WHERE symbol = :symbol",
                new MapSqlParameterSource("symbol", symbol));
    }

    private MapSqlParameterSource toParams(WaypointEntity e) {
        return new MapSqlParameterSource()
                .addValue("symbol", e.getSymbol())
                .addValue("systemSymbol", e.getSystemSymbol())
                .addValue("type", e.getType())
                .addValue("x", e.getX())
                .addValue("y", e.getY())
                .addValue("rawJson", e.getRawJson())
                .addValue("fetchedAt", e.getFetchedAt());
    }

    private static final RowMapper<WaypointEntity> MAPPER = new RowMapper<>() {
        @Override
        public WaypointEntity mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new WaypointEntity(
                    rs.getString("symbol"),
                    rs.getString("system_symbol"),
                    rs.getString("type"),
                    rs.getInt("x"),
                    rs.getInt("y"),
                    rs.getString("raw_json"),
                    rs.getString("fetched_at")
            );
        }
    };
}
