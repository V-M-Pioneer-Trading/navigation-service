package de.vnm.navigation.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SqliteDirectoryInitializerTest {

    /**
     * Regression: SQLite does not create missing parent directories, so a fresh clone using
     * the documented default of {@code ./data/navigation.db} died at startup with
     * "path to './data/navigation.db': './data' does not exist". The README claimed the
     * database was created on first run; it was not.
     */
    @Test
    void createsTheMissingDatabaseDirectory(@TempDir Path tmp) {
        Path db = tmp.resolve("data").resolve("navigation.db");
        assertThat(Files.isDirectory(db.getParent())).isFalse();

        SqliteDirectoryInitializer.createParentDirectory("jdbc:sqlite:" + db);

        assertThat(Files.isDirectory(db.getParent())).isTrue();
    }

    @Test
    void existingDirectoryIsLeftAlone(@TempDir Path tmp) {
        SqliteDirectoryInitializer.createParentDirectory("jdbc:sqlite:" + tmp.resolve("nav.db"));

        assertThat(Files.isDirectory(tmp)).isTrue();
    }

    @Test
    void nonFilesystemUrlsAreIgnored(@TempDir Path tmp) {
        SqliteDirectoryInitializer.createParentDirectory("jdbc:sqlite::memory:");
        SqliteDirectoryInitializer.createParentDirectory("jdbc:sqlite:file:nav?mode=memory");
        SqliteDirectoryInitializer.createParentDirectory("jdbc:postgresql://host/db");

        assertThat(tmp).isEmptyDirectory();
    }
}
