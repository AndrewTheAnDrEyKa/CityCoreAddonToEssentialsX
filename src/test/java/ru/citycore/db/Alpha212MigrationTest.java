package ru.citycore.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Alpha212MigrationTest {
    @TempDir Path temp;
    private Database database;

    @AfterEach void close() { if (database != null) database.close(); }

    @Test void legacyDepositSurvivesAndReceivesExplicitSourceType() {
        database = new Database(temp.resolve("citycore.db"), 1);
        Migrations.apply(database, 7);
        String player = UUID.randomUUID().toString();
        String city = UUID.randomUUID().toString();
        String deposit = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        database.transaction(connection -> {
            try (var profile = connection.prepareStatement(
                    "INSERT INTO player_profile(uuid,last_name,created_at,updated_at) VALUES(?,?,?,?)")) {
                profile.setString(1, player); profile.setString(2, "LegacyOwner");
                profile.setString(3, now); profile.setString(4, now); profile.executeUpdate();
            }
            try (var insertCity = connection.prepareStatement(
                    "INSERT INTO city(id,slug,name,founder_uuid,status,created_at) VALUES(?,?,?,?,?,?)")) {
                insertCity.setString(1, city); insertCity.setString(2, "legacy_oil_city");
                insertCity.setString(3, "Старый нефтяной город"); insertCity.setString(4, player);
                insertCity.setString(5, "ACTIVE"); insertCity.setString(6, now); insertCity.executeUpdate();
            }
            try (var insertDeposit = connection.prepareStatement("""
                    INSERT INTO resource_deposit(id,slug,name,city_id,world_uuid,world_name,x,y,z,radius,
                        reserve_total,reserve_remaining,throughput_limit,status,created_by,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?)
                    """)) {
                insertDeposit.setString(1, deposit); insertDeposit.setString(2, "legacy_deposit");
                insertDeposit.setString(3, "Старое месторождение"); insertDeposit.setString(4, city);
                insertDeposit.setString(5, UUID.randomUUID().toString()); insertDeposit.setString(6, "world");
                insertDeposit.setInt(7, 0); insertDeposit.setInt(8, 64); insertDeposit.setInt(9, 0);
                insertDeposit.setInt(10, 25); insertDeposit.setLong(11, 15_000);
                insertDeposit.setLong(12, 15_000); insertDeposit.setLong(13, 10);
                insertDeposit.setString(14, player); insertDeposit.setString(15, now);
                insertDeposit.setString(16, now); insertDeposit.executeUpdate();
            }
            return null;
        });

        Migrations.apply(database);

        database.transaction(connection -> {
            try (var query = connection.prepareStatement(
                    "SELECT name,reserve_remaining,source_type FROM resource_deposit WHERE id=?")) {
                query.setString(1, deposit);
                try (var rs = query.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("Старое месторождение", rs.getString(1));
                    assertEquals(15_000, rs.getLong(2));
                    assertEquals("LEGACY", rs.getString(3));
                }
            }
            return null;
        });
    }
}
