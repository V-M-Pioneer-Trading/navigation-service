package de.vnm.navigation.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Records which systems have had a <em>complete</em> waypoint walk cached.
 *
 * <p>The {@code waypoints} table cannot answer this on its own: a row exists there for any
 * waypoint ever fetched individually, so "the system has rows" and "the system is fully
 * cached" are different questions. Only the second one may satisfy a listing request.
 */
@Repository
public class SystemCacheRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SystemCacheRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Whether a full listing for this system has been fetched and cached. */
    public boolean isComplete(String systemSymbol) {
        Integer found = jdbc.query(
                "SELECT 1 FROM system_fetches WHERE system_symbol = :systemSymbol",
                new MapSqlParameterSource("systemSymbol", systemSymbol),
                rs -> rs.next() ? 1 : null);
        return found != null;
    }

    /** Mark a system as fully cached as of now. */
    public void markComplete(String systemSymbol) {
        jdbc.update("""
                INSERT INTO system_fetches (system_symbol, fetched_at)
                VALUES (:systemSymbol, :fetchedAt)
                ON CONFLICT(system_symbol) DO UPDATE SET fetched_at = excluded.fetched_at
                """,
                new MapSqlParameterSource()
                        .addValue("systemSymbol", systemSymbol)
                        .addValue("fetchedAt", Instant.now().toString()));
    }
}
