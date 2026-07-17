package ru.citycore.business;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.citycore.city.CityRole;
import ru.citycore.city.CityService;
import ru.citycore.db.Database;
import ru.citycore.db.Migrations;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BusinessRegistrationFlowTest {
    @TempDir Path temp;
    private Database database;

    @AfterEach void close() { if (database != null) database.close(); }

    @Test void directionReviewQueueAndOfficialDecisionFormOneCompleteFlow() {
        Setup setup = setup();

        BusinessService.BusinessView pending = setup.businesses().register(setup.citizen(), "service_lab",
                "Сервисная мастерская", BusinessActivity.SERVICES.name(), null, "");

        assertEquals("SERVICES", pending.activityType());
        assertEquals("PENDING", pending.status());
        assertTrue(pending.reviewRequired());
        assertEquals(1, setup.businesses().pendingReviewCount(setup.mayor()));
        assertEquals(1, setup.businesses().pendingReviewCount(setup.official()));
        assertTrue(setup.businesses().reviewerIds(pending.cityId()).containsAll(
                java.util.List.of(setup.mayor(), setup.official())));

        setup.businesses().decide(setup.official(), pending.id(), true);

        BusinessService.BusinessDetail approved = setup.businesses().detail(setup.citizen(), pending.id());
        assertEquals("ACTIVE", approved.status());
        assertNotNull(approved.accountId());
        assertEquals(0, setup.businesses().pendingReviewCount(setup.mayor()));
    }

    @Test void smallRetailStartsImmediatelyWithoutGovernmentReview() {
        Setup setup = setup();

        BusinessService.BusinessView business = setup.businesses().register(setup.citizen(), "apple_stall",
                "Яблочный ларёк", BusinessActivity.SMALL_RETAIL.name(), null, "");

        assertEquals("ACTIVE", business.status());
        assertFalse(business.reviewRequired());
        assertNotNull(business.accountId());
        assertEquals(0, setup.businesses().pendingReviewCount(setup.mayor()));
    }

    @Test void oilQuestionnairePersistsEstimatedLevelAndDescription() {
        Setup setup = setup();
        String note = "Планируется компактная вышка с базовым комплектом оборудования";

        BusinessService.BusinessView business = setup.businesses().register(setup.citizen(), "north_oil",
                "Северная нефть", BusinessActivity.OIL_EXTRACTION.name(), 2, note);
        BusinessService.BusinessDetail detail = setup.businesses().detail(setup.official(), business.id());

        assertEquals("OIL_EXTRACTION", detail.activityType());
        assertEquals(2, detail.requestedIndustryLevel());
        assertEquals(note, detail.applicationNote());
        assertEquals("PENDING", detail.status());
        assertFalse(detail.canOpenOilIndustry(), "до одобрения нефтяной раздел закрыт");
        setup.businesses().decide(setup.official(), business.id(), true);
        assertTrue(setup.businesses().detail(setup.citizen(), business.id()).canOpenOilIndustry(),
                "одобренное новое нефтяное предприятие должно открывать отраслевой раздел");
        assertThrows(IllegalArgumentException.class, () -> setup.businesses().register(setup.citizen(),
                "bad_oil", "Неполная заявка", BusinessActivity.OIL_EXTRACTION.name(), null, "коротко"));
    }

    @Test void applicantCannotApproveOwnRegistrationAndApplicationsStayOutOfRegistry() {
        Setup setup = setup();
        setup.businesses().register(setup.official(), "own_oil", "Собственная нефть",
                BusinessActivity.OIL_EXTRACTION.name(), 1,
                "Проверка запрета самостоятельного решения по нефтяному предприятию");

        assertTrue(setup.businesses().list(setup.official(), false).isEmpty());
        assertTrue(setup.businesses().list(setup.official(), true).isEmpty());
        BusinessService.BusinessView application = setup.businesses().applications(setup.official()).getFirst();
        assertEquals("PENDING", application.status());
        assertThrows(RuntimeException.class,
                () -> setup.businesses().decide(setup.official(), application.id(), true));
    }

    private Setup setup() {
        database = new Database(temp.resolve("citycore.db"), 1);
        Migrations.apply(database);
        UUID mayor = profile("Mayor");
        UUID official = profile("Official");
        UUID citizen = profile("Citizen");
        CityService cities = new CityService(database);
        var foundation = cities.submitFoundation(mayor, "business_city", "Деловой город",
                "Город создаётся для проверки регистрации предприятий");
        cities.decideFoundation(UUID.randomUUID(), foundation.id(), true, "Одобрено тестом");
        cities.apply(official, "business_city");
        cities.decide(mayor, official, true);
        cities.setRole(mayor, official, CityRole.OFFICIAL);
        cities.apply(citizen, "business_city");
        cities.decide(mayor, citizen, true);
        return new Setup(mayor, official, citizen, new BusinessService(database));
    }

    private UUID profile(String name) {
        UUID id = UUID.randomUUID();
        database.transaction(connection -> {
            try (var insert = connection.prepareStatement(
                    "INSERT INTO player_profile(uuid,last_name,created_at,updated_at) VALUES(?,?,?,?)")) {
                String now = Instant.now().toString();
                insert.setString(1, id.toString()); insert.setString(2, name);
                insert.setString(3, now); insert.setString(4, now); insert.executeUpdate();
            }
            return null;
        });
        return id;
    }

    private record Setup(UUID mayor, UUID official, UUID citizen, BusinessService businesses) {}
}
