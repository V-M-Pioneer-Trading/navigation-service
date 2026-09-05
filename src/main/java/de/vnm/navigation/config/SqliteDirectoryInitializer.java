package de.vnm.navigation.config;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Creates the directory the SQLite file lives in before anything opens a connection.
 *
 * <p>SQLite will not create missing parent directories: with the documented default of
 * {@code ./data/navigation.db}, a fresh clone failed at startup with
 * {@code path to './data/navigation.db': './data' does not exist}. This runs on
 * {@link ApplicationEnvironmentPreparedEvent} because that is the last point where the
 * datasource URL is fully resolved and no bean has used it yet.
 */
public final class SqliteDirectoryInitializer
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    private static final String PREFIX = "jdbc:sqlite:";

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        createParentDirectory(event.getEnvironment().getProperty("spring.datasource.url", ""));
    }

    /** Package-private for testing: {@code jdbc:sqlite:./data/nav.db} creates {@code ./data}. */
    static void createParentDirectory(String jdbcUrl) {
        if (!jdbcUrl.startsWith(PREFIX)) {
            return;
        }
        String path = jdbcUrl.substring(PREFIX.length());
        // ":memory:" and "file:...?mode=memory" are not filesystem paths.
        if (path.isBlank() || path.startsWith(":") || path.startsWith("file:")) {
            return;
        }
        Path parent = Path.of(path).toAbsolutePath().getParent();
        if (parent == null || Files.isDirectory(parent)) {
            return;
        }
        try {
            Files.createDirectories(parent);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create SQLite database directory " + parent, e);
        }
    }
}
