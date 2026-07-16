package ru.citycore.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class Alpha18MigrationTest {
    @TempDir Path temp;
    private Database database;

    @AfterEach void close() { if (database != null) database.close(); }

    @Test void versionFourDataSurvivesUpgradeToAlpha18() {
        Path file = temp.resolve("citycore.db");
        database = new Database(file, 1);
        Migrations.apply(database, 4);
        String player = UUID.randomUUID().toString(); String city = UUID.randomUUID().toString();
        String business = UUID.randomUUID().toString(); String account = UUID.randomUUID().toString();
        String license = UUID.randomUUID().toString(); String now = Instant.now().toString();
        database.transaction(connection -> {
            try (var profile = connection.prepareStatement("INSERT INTO player_profile(uuid,last_name,created_at,updated_at) VALUES(?,?,?,?)");
                 var cityInsert = connection.prepareStatement("INSERT INTO city(id,slug,name,founder_uuid,status,created_at) VALUES(?,'legacy_city','Старый город',?,'ACTIVE',?)");
                 var citizen = connection.prepareStatement("INSERT INTO citizenship(player_uuid,city_id,role,joined_at) VALUES(?,?,'MAYOR',?)");
                 var accountInsert = connection.prepareStatement("INSERT INTO account(id,owner_type,owner_id,currency,balance_minor,version) VALUES(?,'BUSINESS',?,'ESSENTIALS',12345,0)");
                 var businessInsert = connection.prepareStatement("INSERT INTO business(id,city_id,owner_uuid,slug,name,status,created_at) VALUES(?,?,?,'legacy_business','Старое предприятие','ACTIVE',?)");
                 var licenseInsert = connection.prepareStatement("INSERT INTO license(id,business_id,license_type,status,issued_at,expires_at,issued_by) VALUES(?,?,'TRADE','ACTIVE',?,?,?)")) {
                profile.setString(1, player); profile.setString(2, "LegacyMayor"); profile.setString(3, now); profile.setString(4, now); profile.executeUpdate();
                cityInsert.setString(1, city); cityInsert.setString(2, player); cityInsert.setString(3, now); cityInsert.executeUpdate();
                citizen.setString(1, player); citizen.setString(2, city); citizen.setString(3, now); citizen.executeUpdate();
                accountInsert.setString(1, account); accountInsert.setString(2, business); accountInsert.executeUpdate();
                businessInsert.setString(1, business); businessInsert.setString(2, city); businessInsert.setString(3, player); businessInsert.setString(4, now); businessInsert.executeUpdate();
                licenseInsert.setString(1, license); licenseInsert.setString(2, business); licenseInsert.setString(3, now);
                licenseInsert.setString(4, Instant.now().plusSeconds(86400).toString()); licenseInsert.setString(5, player); licenseInsert.executeUpdate();
            }
            return null;
        });

        Migrations.apply(database);

        database.transaction(connection -> {
            try (var query = connection.prepareStatement("SELECT b.activity_type,a.balance_minor,l.status FROM business b JOIN account a ON a.owner_id=b.id JOIN license l ON l.business_id=b.id WHERE b.id=?")) {
                query.setString(1, business); try (var rs = query.executeQuery()) {
                    assertTrue(rs.next()); assertEquals("GENERAL", rs.getString(1));
                    assertEquals(12345, rs.getLong(2)); assertEquals("ACTIVE", rs.getString(3));
                }
            }
            try (var version = connection.createStatement(); var rs = version.executeQuery("SELECT MAX(version) FROM schema_history")) {
                assertTrue(rs.next()); assertEquals(Migrations.latestVersion(), rs.getInt(1));
            }
            return null;
        });
    }

    @Test void backupIsStableAndCreatedOnlyOnce() throws Exception {
        Path databaseFile = temp.resolve("citycore.db"); Path backups = temp.resolve("backups");
        Files.writeString(databaseFile, "alpha17");
        Path first = DatabaseBackup.beforeAlpha18(databaseFile, backups);
        Files.writeString(databaseFile, "changed");
        Path second = DatabaseBackup.beforeAlpha18(databaseFile, backups);

        assertEquals(first, second);
        assertEquals("alpha17", Files.readString(first));
    }
}
