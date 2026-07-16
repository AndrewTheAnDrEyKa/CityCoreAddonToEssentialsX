package ru.citycore.industry;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.citycore.business.BusinessService;
import ru.citycore.city.CityService;
import ru.citycore.config.IndustryLevelSettings;
import ru.citycore.config.IndustrySettings;
import ru.citycore.db.Database;
import ru.citycore.db.Migrations;
import ru.citycore.economy.InternalLedger;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IndustryServiceTest {
    @TempDir Path temp;
    private Database database;
    private UUID mayor;
    private CityService cities;
    private BusinessService businesses;
    private InternalLedger ledger;
    private IndustryService industry;

    @AfterEach void close() { if (database != null) database.close(); }

    @Test void fundedCycleConsumesFiniteReserveAndCannotReplay() {
        Setup setup = setup(100, 100);
        fundTreasury(200_000);
        IndustryService.ObjectView object = approvedObject(setup, "SERIAL-A");
        businesses.issueLicense(mayor, setup.businessId(), "OIL_EXTRACTION_I", 30);
        industry.start(mayor, object.id()); forceDue(object.id(), Instant.now());

        IndustryService.CycleBatch first = industry.processDue(Instant.now());
        IndustryService.ObjectView after = industry.object(mayor, object.id(), false);

        assertEquals(1, first.cycles()); assertEquals(1, first.produced());
        assertEquals(90, after.reserveRemaining());
        assertEquals(14_000, after.businessBalanceMinor());
        assertEquals(5_000, stateOilRevenue());
        assertEquals(1, cycleCount(object.id()));

        Instant originalDue = cycleDue(object.id()); forceDue(object.id(), originalDue);
        industry.processDue(Instant.now());
        assertEquals(90, industry.object(mayor, object.id(), false).reserveRemaining());
        assertEquals(1, cycleCount(object.id()));
    }

    @Test void twoObjectsShareThroughputAndReserveAtomically() {
        Setup setup = setup(100, 12);
        fundTreasury(500_000);
        IndustryService.ObjectView first = approvedObject(setup, "SERIAL-B1");
        IndustryService.ObjectView second = approvedObject(setup, "SERIAL-B2");
        businesses.issueLicense(mayor, setup.businessId(), "OIL_EXTRACTION_I", 30);
        industry.start(mayor, first.id()); industry.start(mayor, second.id());
        Instant due = Instant.now(); forceDue(first.id(), due); forceDue(second.id(), due);

        industry.processDue(Instant.now());

        assertEquals(88, industry.object(mayor, first.id(), false).reserveRemaining());
        assertEquals(12, extractedTotal());
    }

    @Test void stateBuyerPurchasesAutomaticallyAndCollectsFixedTwentyPercent() {
        Setup setup = setup(100, 100);
        IndustryService.ObjectView object = approvedObject(setup, "SERIAL-C");
        businesses.issueLicense(mayor, setup.businessId(), "OIL_EXTRACTION_I", 30);
        industry.start(mayor, object.id()); forceDue(object.id(), Instant.now());

        industry.processDue(Instant.now());

        IndustryService.ObjectView after = industry.object(mayor, object.id(), false);
        assertEquals(90, after.reserveRemaining());
        assertEquals("ACTIVE", after.status());
        assertEquals(14_000, after.businessBalanceMinor());
        assertEquals(5_000, stateOilRevenue());
    }

    @Test void copiedControllerSerialCannotBePlacedTwice() {
        Setup setup = setup(100, 100);
        IndustryService.ControllerView controller = industry.issueController(mayor, setup.businessId());
        UUID world = UUID.randomUUID();
        industry.placeController(mayor, controller.serial(), world, "world", 1, 64, 1);
        assertThrows(RuntimeException.class,
                () -> industry.placeController(mayor, controller.serial(), world, "world", 2, 64, 2));
    }

    @Test void objectRemainsDraftUntilOwnerSubmitsIt() {
        Setup setup = setup(100, 100);
        IndustryService.ControllerView controller = industry.issueController(mayor, setup.businessId());
        industry.placeController(mayor, controller.serial(), UUID.randomUUID(), "world", 10, 64, 10);
        IndustryService.ObjectView draft = industry.applyForObject(mayor, setup.businessId(), controller.serial(), setup.depositId());

        assertEquals("DRAFT", draft.status());
        assertThrows(RuntimeException.class, () -> industry.inspect(mayor, draft.id(), true, 1, "Рано", false));
        industry.submitObject(mayor, draft.id());
        assertEquals("PENDING_INSPECTION", industry.object(mayor, draft.id(), false).status());
    }

    @Test void missingControllerStopsNextProductionPeriod() {
        Setup setup = setup(100, 100);
        fundTreasury(200_000);
        IndustryService.ObjectView object = approvedObject(setup, "SERIAL-MISSING");
        businesses.issueLicense(mayor, setup.businessId(), "OIL_EXTRACTION_I", 30);
        industry.start(mayor, object.id()); forceDue(object.id(), Instant.now());
        industry.removeController(UUID.randomUUID(), object.controllerSerial(), true);

        industry.processDue(Instant.now());

        IndustryService.ObjectView after = industry.object(mayor, object.id(), false);
        assertEquals(100, after.reserveRemaining());
        assertEquals("MISSING_CONTROLLER", after.status());
    }

    @Test void expiredLicenseStopsProductionWithoutConsumingReserve() {
        Setup setup = setup(100, 100);
        fundTreasury(200_000);
        IndustryService.ObjectView object = approvedObject(setup, "SERIAL-EXPIRED");
        businesses.issueLicense(mayor, setup.businessId(), "OIL_EXTRACTION_I", 30);
        industry.start(mayor, object.id());
        database.transaction(connection -> {
            try (var update = connection.prepareStatement("UPDATE license SET issued_at=?,expires_at=? WHERE business_id=? AND license_type='OIL_EXTRACTION_I'")) {
                update.setString(1, Instant.now().minusSeconds(7200).toString());
                update.setString(2, Instant.now().minusSeconds(3600).toString());
                update.setString(3, setup.businessId()); update.executeUpdate();
            }
            return null;
        });
        forceDue(object.id(), Instant.now());

        industry.processDue(Instant.now());

        IndustryService.ObjectView after = industry.object(mayor, object.id(), false);
        assertEquals(100, after.reserveRemaining());
        assertEquals("SUSPENDED_LICENSE", after.status());
    }

    @Test void inspectionPersistsControllerCoordinates() {
        Setup setup = setup(100, 100);
        IndustryService.ControllerView controller = industry.issueController(mayor, setup.businessId());
        UUID world = UUID.randomUUID();
        industry.placeController(mayor, controller.serial(), world, "inspection_world", 71, 65, -24);
        IndustryService.ObjectView draft = industry.applyForObject(mayor, setup.businessId(), controller.serial(), setup.depositId());
        industry.submitObject(mayor, draft.id());
        industry.inspect(mayor, draft.id(), true, 2, "Координаты сохранены", false);

        database.transaction(connection -> {
            try (var query = connection.prepareStatement("SELECT inspection_world_uuid,inspection_world_name,inspection_x,inspection_y,inspection_z FROM industrial_object WHERE id=?")) {
                query.setString(1, draft.id());
                try (var rs = query.executeQuery()) {
                    assertTrue(rs.next()); assertEquals(world.toString(), rs.getString(1));
                    assertEquals("inspection_world", rs.getString(2)); assertEquals(71, rs.getInt(3));
                    assertEquals(65, rs.getInt(4)); assertEquals(-24, rs.getInt(5));
                }
            }
            return null;
        });
    }

    @Test void restartCatchUpIsBoundedAndDoesNotReplay() {
        Setup setup = setup(200, 100);
        fundTreasury(500_000);
        IndustryService.ObjectView object = approvedObject(setup, "SERIAL-CATCHUP");
        businesses.issueLicense(mayor, setup.businessId(), "OIL_EXTRACTION_I", 30);
        database.transaction(connection -> {
            try (var update = connection.prepareStatement("UPDATE license SET issued_at=? WHERE business_id=? AND license_type='OIL_EXTRACTION_I'")) {
                update.setString(1, Instant.now().minusSeconds(86400).toString());
                update.setString(2, setup.businessId()); update.executeUpdate();
            }
            return null;
        });
        industry.start(mayor, object.id()); forceDue(object.id(), Instant.now().minusSeconds(600));

        IndustryService.CycleBatch first = industry.processDue(Instant.now());
        IndustryService.CycleBatch second = industry.processDue(Instant.now());

        assertEquals(3, first.cycles()); assertEquals(3, first.produced());
        assertEquals(0, second.cycles()); assertEquals(3, cycleCount(object.id()));
        assertEquals(170, industry.object(mayor, object.id(), false).reserveRemaining());
    }

    private Setup setup(long reserve, long throughput) {
        database = new Database(temp.resolve("citycore.db"), 1); Migrations.apply(database);
        mayor = profile(); cities = new CityService(database); ledger = new InternalLedger(database);
        var foundation = cities.submitFoundation(mayor, "oil_city", "Нефтяной город",
                "Город создаётся для проверки промышленного цикла");
        cities.decideFoundation(UUID.randomUUID(), foundation.id(), true, "Одобрено тестом");
        businesses = new BusinessService(database, type -> type.startsWith("OIL_EXTRACTION") ? 7 : 1);
        String business = businesses.register(mayor, "oil_business", "Нефтяное предприятие").id();
        businesses.decide(mayor, business, true); businesses.setActivity(mayor, business, "OIL_EXTRACTION");
        IndustrySettings settings = new IndustrySettings(true, 60, 3, 2_000, 100_000,
                Material.LODESTONE, Map.of(
                1, new IndustryLevelSettings(10, 2_500, 4_000, 7),
                2, new IndustryLevelSettings(25, 2_500, 12_000, 30),
                3, new IndustryLevelSettings(60, 2_500, 36_000, 90)));
        industry = new IndustryService(database, ledger, () -> settings);
        IndustryService.DepositView deposit = industry.createDeposit(UUID.randomUUID(), "oil_city", "deposit_main",
                "Главное месторождение", UUID.randomUUID(), "world", 0, 64, 0, 100, reserve, throughput);
        return new Setup(business, deposit.id());
    }

    private IndustryService.ObjectView approvedObject(Setup setup, String ignoredSerial) {
        IndustryService.ControllerView controller = industry.issueController(mayor, setup.businessId());
        industry.placeController(mayor, controller.serial(), UUID.randomUUID(), "world", ignoredSerial.hashCode(), 64, 0);
        IndustryService.ObjectView object = industry.applyForObject(mayor, setup.businessId(), controller.serial(), setup.depositId());
        industry.submitObject(mayor, object.id());
        return industry.inspect(mayor, object.id(), true, 1, "Проверено тестом", false);
    }

    private void fundTreasury(long treasuryAmount) {
        String system = ledger.ensureAccount("SYSTEM", "TEST_SOURCE", "ESSENTIALS");
        ledger.transfer("test-seed:" + UUID.randomUUID(), system, cities.treasuryAccount(mayor), treasuryAmount, "Тестовый капитал", null);
    }

    private long stateOilRevenue() {
        return scalar("SELECT balance_minor FROM account WHERE owner_type='SYSTEM' AND owner_id='STATE_OIL_REVENUE'");
    }

    private void forceDue(String objectId, Instant due) {
        database.transaction(connection -> {
            try (var update = connection.prepareStatement("UPDATE industrial_object SET next_cycle_at=?,status='ACTIVE' WHERE id=?")) {
                update.setString(1, due.toString()); update.setString(2, objectId); update.executeUpdate();
            }
            return null;
        });
    }

    private long cycleCount(String objectId) { return scalar("SELECT COUNT(*) FROM industrial_cycle WHERE object_id='" + objectId + "'"); }
    private long extractedTotal() { return scalar("SELECT COALESCE(SUM(extracted_units),0) FROM industrial_cycle"); }
    private Instant cycleDue(String objectId) {
        return database.transaction(connection -> {
            try (var query = connection.prepareStatement("SELECT due_at FROM industrial_cycle WHERE object_id=? LIMIT 1")) {
                query.setString(1, objectId); try (var rs = query.executeQuery()) { assertTrue(rs.next()); return Instant.parse(rs.getString(1)); }
            }
        });
    }
    private long scalar(String sql) { return database.transaction(connection -> { try (var query = connection.createStatement(); var rs = query.executeQuery(sql)) { return rs.next() ? rs.getLong(1) : 0; } }); }

    private UUID profile() {
        UUID id = UUID.randomUUID(); database.transaction(connection -> {
            try (var insert = connection.prepareStatement("INSERT INTO player_profile(uuid,last_name,created_at,updated_at) VALUES(?,?,?,?)")) {
                String now = Instant.now().toString(); insert.setString(1, id.toString()); insert.setString(2, "Mayor");
                insert.setString(3, now); insert.setString(4, now); insert.executeUpdate();
            } return null;
        }); return id;
    }

    private record Setup(String businessId, String depositId) {}
}
