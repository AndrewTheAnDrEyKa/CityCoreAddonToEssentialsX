package ru.citycore.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Alpha20MigrationTest {
    @TempDir Path temp;
    private Database database;

    @AfterEach void close() {
        if (database != null) database.close();
    }

    @Test void alpha19ProfileSurvivesCommunicationMigration() {
        database = new Database(temp.resolve("citycore.db"), 1);
        Migrations.apply(database, 5);
        UUID playerId = UUID.randomUUID();
        String now = Instant.now().toString();
        database.transaction(connection -> {
            try (var insert = connection.prepareStatement(
                    "INSERT INTO player_profile(uuid,last_name,created_at,updated_at) VALUES(?,?,?,?)")) {
                insert.setString(1, playerId.toString());
                insert.setString(2, "LegacyAlpha19");
                insert.setString(3, now);
                insert.setString(4, now);
                insert.executeUpdate();
            }
            return null;
        });

        Migrations.apply(database);

        database.transaction(connection -> {
            try (var profile = connection.prepareStatement("SELECT last_name FROM player_profile WHERE uuid=?")) {
                profile.setString(1, playerId.toString());
                try (var result = profile.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("LegacyAlpha19", result.getString(1));
                }
            }
            try (var tables = connection.createStatement();
                 var result = tables.executeQuery(
                         "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('device','communication_event')")) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
            try (var versions = connection.createStatement();
                 var result = versions.executeQuery("SELECT MAX(version) FROM schema_history")) {
                assertTrue(result.next());
                assertEquals(Migrations.latestVersion(), result.getInt(1));
            }
            return null;
        });
    }
}
