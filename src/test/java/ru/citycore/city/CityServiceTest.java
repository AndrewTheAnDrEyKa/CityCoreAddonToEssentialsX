package ru.citycore.city;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.citycore.db.Database;
import ru.citycore.db.Migrations;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CityServiceTest {
    @TempDir Path temp;
    private Database database;

    @AfterEach void close() { if (database != null) database.close(); }

    @Test void foundationNeedsAdminDecisionBeforeCityExists() {
        database = new Database(temp.resolve("citycore.db"), 1);
        Migrations.apply(database);
        UUID founder = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        insertProfile(founder, "Founder");
        CityService cities = new CityService(database);

        CityService.FoundationApplication application = cities.submitFoundation(founder, "new_city",
                "Новый город", "Город для проверки безопасной процедуры основания");

        assertEquals("PENDING", application.status());
        assertNull(cities.view(founder));
        assertEquals(1, cities.pendingFoundations().size());

        CityService.FoundationDecision decision = cities.decideFoundation(admin, application.id(), true,
                "Проверено автоматическим тестом");
        assertTrue(decision.approved());
        assertNotNull(decision.city());
        assertEquals(CityRole.MAYOR, cities.view(founder).role());
        assertEquals(0, cities.view(founder).treasuryMinor());
        CityService.CityStats stats = cities.stats(founder);
        assertEquals(1, stats.population());
        assertEquals(1, stats.mayors());
        assertEquals(0, stats.activeBusinesses());
        assertEquals(0, stats.industrialObjects());
        assertTrue(cities.pendingFoundations().isEmpty());
    }

    @Test void rejectedApplicationDoesNotCreateCity() {
        database = new Database(temp.resolve("citycore.db"), 1);
        Migrations.apply(database);
        UUID founder = UUID.randomUUID();
        insertProfile(founder, "Founder");
        CityService cities = new CityService(database);
        var application = cities.submitFoundation(founder, "rejected_city", "Отклонённый город",
                "Описание заявки, которая не должна создать город");

        var decision = cities.decideFoundation(UUID.randomUUID(), application.id(), false, "Недостаточно данных");

        assertFalse(decision.approved());
        assertNull(decision.city());
        assertNull(cities.view(founder));
        assertEquals("REJECTED", cities.latestFoundation(founder).status());
    }

    private void insertProfile(UUID id, String name) {
        database.transaction(connection -> {
            try (var insert = connection.prepareStatement("INSERT INTO player_profile(uuid,last_name,created_at,updated_at) VALUES(?,?,?,?)")) {
                String now = Instant.now().toString();
                insert.setString(1, id.toString());
                insert.setString(2, name);
                insert.setString(3, now);
                insert.setString(4, now);
                insert.executeUpdate();
            }
            return null;
        });
    }
}
