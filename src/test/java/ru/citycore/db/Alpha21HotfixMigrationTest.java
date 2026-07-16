package ru.citycore.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class Alpha21HotfixMigrationTest {
    @TempDir Path temp;
    private Database database;

    @AfterEach void close() { if (database != null) database.close(); }

    @Test void alpha21BusinessSurvivesRegistrationMetadataMigration() {
        database = new Database(temp.resolve("citycore.db"), 1);
        Migrations.apply(database, 6);
        String player = UUID.randomUUID().toString();
        String city = UUID.randomUUID().toString();
        String business = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        database.transaction(connection -> {
            try (var profile = connection.prepareStatement(
                    "INSERT INTO player_profile(uuid,last_name,created_at,updated_at) VALUES(?,?,?,?)")) {
                profile.setString(1, player); profile.setString(2, "LegacyOwner");
                profile.setString(3, now); profile.setString(4, now); profile.executeUpdate();
            }
            try (var insertCity = connection.prepareStatement(
                    "INSERT INTO city(id,slug,name,founder_uuid,status,created_at) VALUES(?,?,?,?,?,?)")) {
                insertCity.setString(1, city); insertCity.setString(2, "legacy_city");
                insertCity.setString(3, "Старый город"); insertCity.setString(4, player);
                insertCity.setString(5, "ACTIVE"); insertCity.setString(6, now); insertCity.executeUpdate();
            }
            try (var insertBusiness = connection.prepareStatement(
                    "INSERT INTO business(id,city_id,owner_uuid,slug,name,status,created_at,activity_type) VALUES(?,?,?,?,?,?,?,?)")) {
                insertBusiness.setString(1, business); insertBusiness.setString(2, city);
                insertBusiness.setString(3, player); insertBusiness.setString(4, "legacy_shop");
                insertBusiness.setString(5, "Старый магазин"); insertBusiness.setString(6, "ACTIVE");
                insertBusiness.setString(7, now); insertBusiness.setString(8, "GENERAL"); insertBusiness.executeUpdate();
            }
            return null;
        });

        Migrations.apply(database);

        database.transaction(connection -> {
            try (var query = connection.prepareStatement(
                    "SELECT name,requested_industry_level,application_note,review_required FROM business WHERE id=?")) {
                query.setString(1, business);
                try (var rs = query.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("Старый магазин", rs.getString(1));
                    assertNull(rs.getObject(2));
                    assertEquals("", rs.getString(3));
                    assertEquals(1, rs.getInt(4));
                }
            }
            try (var version = connection.createStatement();
                 var rs = version.executeQuery("SELECT MAX(version) FROM schema_history")) {
                assertTrue(rs.next());
                assertEquals(Migrations.latestVersion(), rs.getInt(1));
            }
            return null;
        });
    }
}
