package ru.citycore.city;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.citycore.business.BusinessService;
import ru.citycore.db.Database;
import ru.citycore.db.Migrations;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MembershipLifecycleTest {
    @TempDir Path temp;
    private Database database;

    @AfterEach void close() { if (database != null) database.close(); }

    @Test void officialCanResignAndLeaveWithoutLosingBusiness() {
        database = new Database(temp.resolve("citycore.db"), 1); Migrations.apply(database);
        UUID mayor = profile("Mayor"); UUID citizen = profile("Citizen");
        CityService cities = cityWithMayor(mayor);
        cities.apply(citizen, "lifecycle_city"); cities.decide(mayor, citizen, true);
        BusinessService businesses = new BusinessService(database);
        String business = businesses.register(citizen, "citizen_oil", "Предприятие гражданина").id();
        businesses.decide(mayor, business, true);
        cities.setRole(mayor, citizen, CityRole.OFFICIAL);

        cities.resignOfficial(citizen);
        assertEquals(CityRole.CITIZEN, cities.view(citizen).role());
        cities.leaveCity(citizen);

        assertNull(cities.view(citizen));
        assertEquals(1, businesses.list(citizen, false).size());
        assertEquals(business, businesses.list(citizen, false).get(0).id());
    }

    @Test void acceptedMayorTransferLeavesExactlyOneMayor() {
        database = new Database(temp.resolve("citycore.db"), 1); Migrations.apply(database);
        UUID mayor = profile("Mayor"); UUID candidate = profile("Candidate");
        CityService cities = cityWithMayor(mayor);
        cities.apply(candidate, "lifecycle_city"); cities.decide(mayor, candidate, true);
        CityService.MayorTransfer request = cities.requestMayorTransfer(mayor, candidate);

        cities.decideMayorTransfer(candidate, request.id(), true);

        assertEquals(CityRole.CITIZEN, cities.view(mayor).role());
        assertEquals(CityRole.MAYOR, cities.view(candidate).role());
        assertEquals(1, cities.members(candidate).stream().filter(member -> member.role() == CityRole.MAYOR).count());
    }

    @Test void pendingApplicationsCanBeCancelled() {
        database = new Database(temp.resolve("citycore.db"), 1); Migrations.apply(database);
        UUID mayor = profile("Mayor"); UUID applicant = profile("Applicant");
        CityService cities = cityWithMayor(mayor);
        String id = cities.apply(applicant, "lifecycle_city");
        cities.cancelCitizenshipApplication(applicant, id);
        assertTrue(cities.pending(mayor).isEmpty());
    }

    @Test void mayorCanWithdrawPendingResignation() {
        database = new Database(temp.resolve("citycore.db"), 1); Migrations.apply(database);
        UUID mayor = profile("Mayor"); CityService cities = cityWithMayor(mayor);
        CityService.MayorResignation request = cities.submitMayorResignation(mayor, "Проверка отзыва заявления");

        cities.cancelMayorResignation(mayor, request.id());

        assertNull(cities.pendingMayorResignation(mayor));
        assertTrue(cities.pendingMayorResignations().isEmpty());
    }

    private CityService cityWithMayor(UUID mayor) {
        CityService cities = new CityService(database);
        var foundation = cities.submitFoundation(mayor, "lifecycle_city", "Город жизненного цикла",
                "Город создаётся для проверки обратимых действий");
        cities.decideFoundation(UUID.randomUUID(), foundation.id(), true, "Одобрено тестом");
        return cities;
    }

    private UUID profile(String name) {
        UUID id = UUID.randomUUID();
        database.transaction(connection -> {
            try (var insert = connection.prepareStatement("INSERT INTO player_profile(uuid,last_name,created_at,updated_at) VALUES(?,?,?,?)")) {
                String now = Instant.now().toString(); insert.setString(1, id.toString()); insert.setString(2, name);
                insert.setString(3, now); insert.setString(4, now); insert.executeUpdate();
            }
            return null;
        });
        return id;
    }
}
