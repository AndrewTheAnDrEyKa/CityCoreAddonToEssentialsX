package ru.citycore.industry;

import org.bukkit.Material;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.citycore.business.BusinessService;
import ru.citycore.business.BusinessLicenseType;
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
    private UUID official;
    private UUID owner;
    private CityService cities;
    private BusinessService businesses;
    private InternalLedger ledger;
    private IndustryService industry;

    @AfterEach void close() { if (database != null) database.close(); }

    @Test void fundedCycleConsumesFiniteReserveAndCannotReplay() {
        Setup setup = setup(100, 100);
        fundTreasury(200_000);
        IndustryService.ObjectView object = approvedObject(setup, "SERIAL-A");
        businesses.issueLicense(official, setup.businessId(), "OIL_EXTRACTION_I", 30);
        industry.start(owner, object.id()); forceDue(object.id(), Instant.now());

        IndustryService.CycleBatch first = industry.processDue(Instant.now());
        IndustryService.ObjectView after = industry.object(owner, object.id(), false);

        assertEquals(1, first.cycles()); assertEquals(1, first.produced());
        assertEquals(90, after.reserveRemaining());
        assertEquals(14_000, after.businessBalanceMinor());
        assertEquals(5_000, stateOilRevenue());
        assertEquals(1, cycleCount(object.id()));

        Instant originalDue = cycleDue(object.id()); forceDue(object.id(), originalDue);
        industry.processDue(Instant.now());
        assertEquals(90, industry.object(owner, object.id(), false).reserveRemaining());
        assertEquals(1, cycleCount(object.id()));
    }

    @Test void oneBusinessCannotIssueASecondController() {
        Setup setup = setup(100, 100);
        industry.issueController(owner, setup.businessId());

        assertThrows(RuntimeException.class, () -> industry.issueController(owner, setup.businessId()));
    }

    @Test void stateBuyerPurchasesAutomaticallyAndCollectsFixedTwentyPercent() {
        Setup setup = setup(100, 100);
        IndustryService.ObjectView object = approvedObject(setup, "SERIAL-C");
        businesses.issueLicense(official, setup.businessId(), "OIL_EXTRACTION_I", 30);
        industry.start(owner, object.id()); forceDue(object.id(), Instant.now());

        industry.processDue(Instant.now());

        IndustryService.ObjectView after = industry.object(owner, object.id(), false);
        assertEquals(90, after.reserveRemaining());
        assertEquals("ACTIVE", after.status());
        assertEquals(14_000, after.businessBalanceMinor());
        assertEquals(5_000, stateOilRevenue());
    }

    @Test void copiedControllerSerialCannotBePlacedTwice() {
        Setup setup = setup(100, 100);
        IndustryService.ControllerView controller = industry.issueController(owner, setup.businessId());
        UUID world = UUID.randomUUID();
        industry.placeController(owner, controller.serial(), world, "world", 1, 64, 1);
        assertThrows(RuntimeException.class,
                () -> industry.placeController(owner, controller.serial(), world, "world", 2, 64, 2));
    }

    @Test void lostUnplacedControllerItemCanRecoverOnlyForItsOwnerAndSameSerial() {
        Setup setup = setup(100, 100);
        IndustryService.ControllerView issued = industry.issueController(owner, setup.businessId());

        assertEquals(issued.serial(), industry.recoverIssuedController(owner, issued.serial()).serial());
        assertThrows(RuntimeException.class,
                () -> industry.recoverIssuedController(official, issued.serial()));

        industry.placeController(owner, issued.serial(), UUID.randomUUID(), "world", 3, 64, 3);
        assertThrows(RuntimeException.class,
                () -> industry.recoverIssuedController(owner, issued.serial()));
    }

    @Test void placingControllerAutomaticallyCreatesPendingInspection() {
        Setup setup = setup(100, 100);
        IndustryService.ControllerView controller = industry.issueController(owner, setup.businessId());
        IndustryService.ControllerView placed = industry.placeController(owner, controller.serial(), UUID.randomUUID(),
                "world", 10, 64, 10);
        IndustryService.ObjectView application = industry.object(owner, placed.objectId(), false);

        assertEquals("BOUND", placed.state());
        assertEquals("PENDING_INSPECTION", application.status());
        assertEquals(100, application.reserveRemaining());
        assertEquals(25, depositRadius(application.depositId()));
        assertThrows(RuntimeException.class,
                () -> industry.inspect(owner, application.id(), true, 1, "Самопроверка", false));
    }

    @Test void oilLicenseAppearsOnlyAfterInspectionAndMatchesTheApprovedLevel() {
        Setup setup = setup(15_000, 100);
        IndustryService.ControllerView controller = industry.issueController(owner, setup.businessId());
        IndustryService.ControllerView placed = industry.placeController(owner, controller.serial(), UUID.randomUUID(),
                "world", 120, 64, -40);

        assertTrue(businesses.availableLicenses(official, setup.businessId()).isEmpty());
        assertThrows(RuntimeException.class,
                () -> businesses.issueLicense(official, setup.businessId(), "TRADE", 30));

        IndustryService.ObjectView inspected = industry.inspect(official, placed.objectId(), true, 3,
                "Оборудование соответствует крупному промышленному комплексу", false);

        assertEquals(25_000, inspected.reserveTotal());
        assertEquals(25_000, inspected.reserveRemaining());
        assertEquals(java.util.List.of(BusinessLicenseType.OIL_EXTRACTION_III),
                businesses.availableLicenses(official, setup.businessId()));
        assertThrows(RuntimeException.class,
                () -> businesses.issueLicense(official, setup.businessId(), "OIL_EXTRACTION_I", 30));
        assertEquals("OIL_EXTRACTION_III",
                businesses.issueLicense(official, setup.businessId(), "OIL_EXTRACTION_III", 90).type());
    }

    @Test void missingControllerStopsNextProductionPeriod() {
        Setup setup = setup(100, 100);
        fundTreasury(200_000);
        IndustryService.ObjectView object = approvedObject(setup, "SERIAL-MISSING");
        businesses.issueLicense(official, setup.businessId(), "OIL_EXTRACTION_I", 30);
        industry.start(owner, object.id()); forceDue(object.id(), Instant.now());
        industry.removeController(owner, object.controllerSerial(), false);

        industry.processDue(Instant.now());

        IndustryService.ObjectView after = industry.object(owner, object.id(), false);
        assertEquals(100, after.reserveRemaining());
        assertEquals("MISSING_CONTROLLER", after.status());
    }

    @Test void controllerRemovalCreatesTemporaryDowntimeAndCanRestoreOnlyAtOriginalPoint() {
        Setup setup = setup(100, 100);
        IndustryService.ControllerView issued = industry.issueController(owner, setup.businessId());
        UUID world = UUID.randomUUID();
        IndustryService.ControllerView placed = industry.placeController(owner, issued.serial(), world, "world", 20, 64, 30);

        IndustryService.ControllerView removed = industry.removeController(owner, issued.serial(), false);
        assertEquals("ISSUED", removed.state());
        assertEquals("MISSING_CONTROLLER", industry.object(owner, placed.objectId(), false).status());
        assertThrows(RuntimeException.class,
                () -> industry.placeController(owner, issued.serial(), world, "world", 21, 64, 30));

        IndustryService.ControllerView restored = industry.placeController(owner, issued.serial(), world, "world", 20, 64, 30);
        assertEquals("BOUND", restored.state());
        assertEquals("PENDING_INSPECTION", industry.object(owner, placed.objectId(), false).status());
    }

    @Test void decommissionedControllerRetiresAndNewSiteCannotDuplicateOldCoordinates() {
        Setup setup = setup(100, 100);
        IndustryService.ControllerView first = industry.issueController(owner, setup.businessId());
        UUID world = UUID.randomUUID();
        IndustryService.ControllerView placed = industry.placeController(owner, first.serial(), world, "world", 50, 64, 50);
        industry.decommission(owner, placed.objectId());

        assertEquals("RETIRED", industry.removeController(owner, first.serial(), false).state());
        IndustryService.ControllerView replacement = industry.issueController(owner, setup.businessId());
        assertThrows(RuntimeException.class,
                () -> industry.placeController(owner, replacement.serial(), world, "world", 50, 64, 50));
    }

    @Test void expiredLicenseStopsProductionWithoutConsumingReserve() {
        Setup setup = setup(100, 100);
        fundTreasury(200_000);
        IndustryService.ObjectView object = approvedObject(setup, "SERIAL-EXPIRED");
        businesses.issueLicense(official, setup.businessId(), "OIL_EXTRACTION_I", 30);
        industry.start(owner, object.id());
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

        IndustryService.ObjectView after = industry.object(owner, object.id(), false);
        assertEquals(100, after.reserveRemaining());
        assertEquals("SUSPENDED_LICENSE", after.status());
    }

    @Test void inspectionPersistsControllerCoordinates() {
        Setup setup = setup(100, 100);
        IndustryService.ControllerView controller = industry.issueController(owner, setup.businessId());
        UUID world = UUID.randomUUID();
        IndustryService.ControllerView placed = industry.placeController(owner, controller.serial(), world,
                "inspection_world", 71, 65, -24);
        industry.inspect(official, placed.objectId(), true, 2, "Координаты сохранены", false);

        database.transaction(connection -> {
            try (var query = connection.prepareStatement("SELECT inspection_world_uuid,inspection_world_name,inspection_x,inspection_y,inspection_z FROM industrial_object WHERE id=?")) {
                query.setString(1, placed.objectId());
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
        businesses.issueLicense(official, setup.businessId(), "OIL_EXTRACTION_I", 30);
        database.transaction(connection -> {
            try (var update = connection.prepareStatement("UPDATE license SET issued_at=? WHERE business_id=? AND license_type='OIL_EXTRACTION_I'")) {
                update.setString(1, Instant.now().minusSeconds(86400).toString());
                update.setString(2, setup.businessId()); update.executeUpdate();
            }
            return null;
        });
        industry.start(owner, object.id()); forceDue(object.id(), Instant.now().minusSeconds(600));

        IndustryService.CycleBatch first = industry.processDue(Instant.now());
        IndustryService.CycleBatch second = industry.processDue(Instant.now());

        assertEquals(3, first.cycles()); assertEquals(3, first.produced());
        assertEquals(0, second.cycles()); assertEquals(3, cycleCount(object.id()));
        assertEquals(170, industry.object(owner, object.id(), false).reserveRemaining());
    }

    private Setup setup(long reserve, long throughput) {
        database = new Database(temp.resolve("citycore.db"), 1); Migrations.apply(database);
        mayor = profile(); official = profile(); owner = profile();
        cities = new CityService(database); ledger = new InternalLedger(database);
        var foundation = cities.submitFoundation(mayor, "oil_city", "Нефтяной город",
                "Город создаётся для проверки промышленного цикла");
        cities.decideFoundation(UUID.randomUUID(), foundation.id(), true, "Одобрено тестом");
        cities.apply(official, "oil_city"); cities.decide(mayor, official, true);
        cities.setRole(mayor, official, ru.citycore.city.CityRole.OFFICIAL);
        cities.apply(owner, "oil_city"); cities.decide(mayor, owner, true);
        cities.setRole(mayor, owner, ru.citycore.city.CityRole.OFFICIAL);
        businesses = new BusinessService(database, type -> type.startsWith("OIL_EXTRACTION") ? 7 : 1);
        String business = businesses.register(owner, "oil_business", "Нефтяное предприятие",
                "OIL_EXTRACTION", 1, "Автоматический нефтяной участок для интеграционной проверки").id();
        businesses.decide(official, business, true);
        IndustrySettings settings = new IndustrySettings(true, 60, 3, 2_000, 100_000,
                25, Map.of(1, reserve, 2, reserve, 3, reserve == 15_000 ? 25_000L : reserve),
                Material.LODESTONE, Map.of(
                1, new IndustryLevelSettings(10, 2_500, 4_000, 7),
                2, new IndustryLevelSettings(25, 2_500, 12_000, 30),
                3, new IndustryLevelSettings(60, 2_500, 36_000, 90)));
        industry = new IndustryService(database, ledger, () -> settings);
        return new Setup(business);
    }

    private IndustryService.ObjectView approvedObject(Setup setup, String ignoredSerial) {
        IndustryService.ControllerView controller = industry.issueController(owner, setup.businessId());
        IndustryService.ControllerView placed = industry.placeController(owner, controller.serial(), UUID.randomUUID(),
                "world", ignoredSerial.hashCode(), 64, 0);
        return industry.inspect(official, placed.objectId(), true, 1, "Проверено другим должностным лицом", false);
    }

    private void fundTreasury(long treasuryAmount) {
        String system = ledger.ensureAccount("SYSTEM", "TEST_SOURCE", "ESSENTIALS");
        ledger.transfer("test-seed:" + UUID.randomUUID(), system, cities.treasuryAccount(owner), treasuryAmount, "Тестовый капитал", null);
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
    private long depositRadius(String depositId) { return scalar("SELECT radius FROM resource_deposit WHERE id='" + depositId + "'"); }
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

    private record Setup(String businessId) {}
}
