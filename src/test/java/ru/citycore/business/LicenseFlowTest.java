package ru.citycore.business;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.citycore.city.CityService;
import ru.citycore.db.Database;
import ru.citycore.db.Migrations;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LicenseFlowTest {
    @TempDir Path temp;
    private Database database;

    @AfterEach void close() { if (database != null) database.close(); }

    @Test void tradeForThirtyDaysIsAcceptedAfterNormalization() {
        database = new Database(temp.resolve("citycore.db"), 1); Migrations.apply(database);
        UUID mayor = profile(); CityService cities = new CityService(database);
        var foundation = cities.submitFoundation(mayor, "license_city", "Город лицензий",
                "Город создаётся для проверки выдачи лицензий");
        cities.decideFoundation(UUID.randomUUID(), foundation.id(), true, "Одобрено тестом");
        BusinessService businesses = new BusinessService(database);
        String id = businesses.register(mayor, "trade_test", "Торговое предприятие").id();
        businesses.decide(mayor, id, true);

        BusinessService.LicenseView license = businesses.issueLicense(mayor, id, "\uFEFF\u00a0tra\u200Bde\u00a0", 30);

        assertEquals("TRADE", license.type());
        assertEquals("ACTIVE", license.status());
    }

    private UUID profile() {
        UUID id = UUID.randomUUID();
        database.transaction(connection -> {
            try (var insert = connection.prepareStatement("INSERT INTO player_profile(uuid,last_name,created_at,updated_at) VALUES(?,?,?,?)")) {
                String now = Instant.now().toString(); insert.setString(1, id.toString()); insert.setString(2, "Mayor");
                insert.setString(3, now); insert.setString(4, now); insert.executeUpdate();
            }
            return null;
        });
        return id;
    }
}
