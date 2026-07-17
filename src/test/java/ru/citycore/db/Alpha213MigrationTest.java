package ru.citycore.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.citycore.business.BusinessActivity;
import ru.citycore.business.BusinessLicenseType;
import ru.citycore.business.BusinessService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Alpha213MigrationTest {
    @TempDir Path temp;
    private Database database;

    @AfterEach void close() { if (database != null) database.close(); }

    @Test void legacyOilApplicationIsReclassifiedAndWrongTradeLicenseIsRevoked() {
        database = new Database(temp.resolve("citycore.db"), 1);
        Migrations.apply(database, 8);
        UUID mayor = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        String city = UUID.randomUUID().toString();
        String legacyOil = UUID.randomUUID().toString();
        String validTrade = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        database.transaction(connection -> {
            insertProfile(connection, mayor, "Mayor", now);
            insertProfile(connection, owner, "Owner", now);
            try (var insertCity = connection.prepareStatement(
                    "INSERT INTO city(id,slug,name,founder_uuid,status,created_at) VALUES(?,?,?,?,?,?)")) {
                insertCity.setString(1, city); insertCity.setString(2, "migration_city");
                insertCity.setString(3, "Город миграции"); insertCity.setString(4, mayor.toString());
                insertCity.setString(5, "ACTIVE"); insertCity.setString(6, now); insertCity.executeUpdate();
            }
            insertCitizenship(connection, mayor, city, "MAYOR", now);
            insertCitizenship(connection, owner, city, "CITIZEN", now);
            insertBusiness(connection, legacyOil, city, owner, "legacy_oil", "Старая нефтедобыча",
                    "TRADE", 2, "Промышленная нефтяная установка второго уровня", now);
            insertBusiness(connection, validTrade, city, owner, "valid_trade", "Обычный магазин",
                    "TRADE", null, "", now);
            insertTradeLicense(connection, legacyOil, mayor, "legacy-trade-license", now);
            insertTradeLicense(connection, validTrade, mayor, "valid-trade-license", now);
            return null;
        });

        Migrations.apply(database);

        database.transaction(connection -> {
            try (var query = connection.prepareStatement(
                    "SELECT activity_type,requested_industry_level FROM business WHERE id=?")) {
                query.setString(1, legacyOil);
                try (var rs = query.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("OIL_EXTRACTION", rs.getString(1));
                    assertEquals(2, rs.getInt(2));
                }
                query.setString(1, validTrade);
                try (var rs = query.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("TRADE", rs.getString(1));
                }
            }
            try (var query = connection.prepareStatement(
                    "SELECT status FROM license WHERE id=?")) {
                query.setString(1, "legacy-trade-license");
                try (var rs = query.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("REVOKED", rs.getString(1));
                }
                query.setString(1, "valid-trade-license");
                try (var rs = query.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("ACTIVE", rs.getString(1));
                }
            }
            return null;
        });

        BusinessService businesses = new BusinessService(database);
        BusinessService.BusinessDetail repaired = businesses.detail(owner, legacyOil);
        assertEquals(BusinessActivity.OIL_EXTRACTION.name(), repaired.activityType());
        assertTrue(repaired.canOpenOilIndustry(), "после миграции владелец должен получить нефтяной маршрут GUI");
        assertTrue(repaired.licenses().isEmpty(), "ошибочная TRADE не должна оставаться в карточке нефтедобычи");
        assertTrue(businesses.availableLicenses(mayor, legacyOil).isEmpty(),
                "до осмотра объекта нефтяных разрешений нет");
        assertEquals(List.of(BusinessLicenseType.TRADE), businesses.availableLicenses(mayor, validTrade));
        assertTrue(businesses.oilDataHealth().healthy());
        assertEquals(1, businesses.oilDataHealth().oilBusinesses());
    }

    private void insertProfile(java.sql.Connection connection, UUID id, String name, String now) throws Exception {
        try (var insert = connection.prepareStatement(
                "INSERT INTO player_profile(uuid,last_name,created_at,updated_at) VALUES(?,?,?,?)")) {
            insert.setString(1, id.toString()); insert.setString(2, name);
            insert.setString(3, now); insert.setString(4, now); insert.executeUpdate();
        }
    }

    private void insertCitizenship(java.sql.Connection connection, UUID player, String city,
                                   String role, String now) throws Exception {
        try (var insert = connection.prepareStatement(
                "INSERT INTO citizenship(player_uuid,city_id,role,joined_at) VALUES(?,?,?,?)")) {
            insert.setString(1, player.toString()); insert.setString(2, city);
            insert.setString(3, role); insert.setString(4, now); insert.executeUpdate();
        }
    }

    private void insertBusiness(java.sql.Connection connection, String id, String city, UUID owner,
                                String slug, String name, String activity, Integer requestedLevel,
                                String note, String now) throws Exception {
        try (var insert = connection.prepareStatement("""
                INSERT INTO business(id,city_id,owner_uuid,slug,name,status,created_at,activity_type,
                                     requested_industry_level,application_note,review_required)
                VALUES(?,?,?,?,?,'ACTIVE',?,?,?,?,1)
                """)) {
            insert.setString(1, id); insert.setString(2, city); insert.setString(3, owner.toString());
            insert.setString(4, slug); insert.setString(5, name); insert.setString(6, now);
            insert.setString(7, activity);
            if (requestedLevel == null) insert.setNull(8, java.sql.Types.INTEGER); else insert.setInt(8, requestedLevel);
            insert.setString(9, note); insert.executeUpdate();
        }
    }

    private void insertTradeLicense(java.sql.Connection connection, String business, UUID mayor,
                                    String id, String now) throws Exception {
        try (var insert = connection.prepareStatement("""
                INSERT INTO license(id,business_id,license_type,status,issued_at,expires_at,issued_by)
                VALUES(?,?,'TRADE','ACTIVE',?,?,?)
                """)) {
            insert.setString(1, id); insert.setString(2, business); insert.setString(3, now);
            insert.setString(4, Instant.parse(now).plusSeconds(86_400).toString());
            insert.setString(5, mayor.toString()); insert.executeUpdate();
        }
    }
}
