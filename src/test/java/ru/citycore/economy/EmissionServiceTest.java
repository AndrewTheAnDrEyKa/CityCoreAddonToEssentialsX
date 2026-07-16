package ru.citycore.economy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.citycore.city.CityService;
import ru.citycore.db.Database;
import ru.citycore.db.Migrations;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EmissionServiceTest {
    @TempDir Path temp;
    private Database database;

    @AfterEach void close() { if (database != null) database.close(); }

    @Test void issueCreditsTreasuryAndCreatesAuditBackedRecord() {
        database = new Database(temp.resolve("citycore.db"), 1);
        Migrations.apply(database);
        UUID founder = UUID.randomUUID();
        insertProfile(founder, "Founder");
        CityService cities = new CityService(database);
        var application = cities.submitFoundation(founder, "issue_city", "Город эмиссии",
                "Город создаётся для проверки контролируемой эмиссии");
        cities.decideFoundation(UUID.randomUUID(), application.id(), true, "Одобрено тестом");
        EmissionService emission = new EmissionService(database, new InternalLedger(database), () -> 1_000_000);

        EmissionService.Issue issue = emission.issueToCity(UUID.randomUUID(), "issue_city", 25_000,
                "Стартовый бюджет по решению администрации");

        assertEquals("COMPLETED", issue.state());
        assertNotNull(issue.ledgerEntryId());
        assertEquals(25_000, cities.view(founder).treasuryMinor());
        assertEquals(1, emission.recent(10).size());
        assertEquals(0, emission.recoverIncomplete());
    }

    @Test void issueRequiresMeaningfulReason() {
        database = new Database(temp.resolve("citycore.db"), 1);
        Migrations.apply(database);
        EmissionService emission = new EmissionService(database, new InternalLedger(database), () -> 1_000_000);
        assertThrows(IllegalArgumentException.class,
                () -> emission.issueToCity(UUID.randomUUID(), "missing", 100, "нет"));
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
