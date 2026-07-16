package ru.citycore.industry;

import ru.citycore.audit.AuditLog;
import ru.citycore.city.CityRole;
import ru.citycore.config.IndustryLevelSettings;
import ru.citycore.config.IndustrySettings;
import ru.citycore.db.Database;
import ru.citycore.economy.InternalLedger;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Database-authoritative industrial vertical slice for finite oil extraction. */
public final class IndustryService {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9_-]{2,23}");
    public static final int STATE_OIL_SALES_TAX_BPS = 2_000;
    private static final String STATE_PROCUREMENT_ACCOUNT = "STATE_OIL_PROCUREMENT";
    private static final String STATE_REVENUE_ACCOUNT = "STATE_OIL_REVENUE";
    private final Database database;
    private final InternalLedger ledger;
    private final Supplier<IndustrySettings> settings;

    public IndustryService(Database database, InternalLedger ledger, Supplier<IndustrySettings> settings) {
        this.database = database;
        this.ledger = ledger;
        this.settings = settings;
    }

    public DepositView createDeposit(UUID actor, String rawCitySlug, String rawSlug, String rawName,
                                     UUID worldId, String worldName, int x, int y, int z,
                                     int radius, long reserve, long throughput) {
        String citySlug = normalizeSlug(rawCitySlug, "ID города");
        String slug = normalizeSlug(rawSlug, "ID месторождения");
        String name = rawName == null ? "" : rawName.strip();
        if (name.length() < 3 || name.length() > 48) throw new IllegalArgumentException("Название месторождения: от 3 до 48 символов");
        if (radius < 1 || radius > 1024) throw new IllegalArgumentException("Радиус месторождения: от 1 до 1024 блоков");
        if (reserve <= 0 || throughput <= 0) throw new IllegalArgumentException("Запас и пропускная способность должны быть положительными");
        if (worldId == null || worldName == null || worldName.isBlank()) throw new IllegalArgumentException("Мир месторождения не определён");
        return database.transaction(connection -> {
            String cityId = cityIdBySlug(connection, citySlug);
            String id = UUID.randomUUID().toString(); Instant now = Instant.now();
            try (var insert = connection.prepareStatement("""
                    INSERT INTO resource_deposit(id,slug,name,city_id,world_uuid,world_name,x,y,z,radius,
                    reserve_total,reserve_remaining,throughput_limit,status,created_by,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?)
                    """)) {
                insert.setString(1, id); insert.setString(2, slug); insert.setString(3, name); insert.setString(4, cityId);
                insert.setString(5, worldId.toString()); insert.setString(6, worldName);
                insert.setInt(7, x); insert.setInt(8, y); insert.setInt(9, z); insert.setInt(10, radius);
                insert.setLong(11, reserve); insert.setLong(12, reserve); insert.setLong(13, throughput);
                insert.setString(14, actor.toString()); insert.setString(15, now.toString()); insert.setString(16, now.toString());
                insert.executeUpdate();
            }
            AuditLog.append(connection, "RESOURCE_DEPOSIT_CREATED", actor, "RESOURCE_DEPOSIT", id,
                    "slug=" + slug + ";city=" + cityId + ";reserve=" + reserve + ";throughput=" + throughput);
            return new DepositView(id, slug, name, cityId, worldId, worldName, x, y, z, radius,
                    reserve, reserve, throughput, "ACTIVE", null, null);
        });
    }

    public List<DepositView> deposits(UUID actor, boolean administrator) {
        return database.transaction(connection -> {
            Authority authority = authority(connection, actor);
            String cityFilter = administrator ? null : authority == null ? "" : authority.cityId();
            if (!administrator && cityFilter.isEmpty()) return List.of();
            return readDeposits(connection, cityFilter, null);
        });
    }

    public List<DepositView> depositsForBusiness(UUID owner, String businessId) {
        return database.transaction(connection -> {
            BusinessRow business = requireBusinessOwner(connection, owner, businessId);
            return readDeposits(connection, business.cityId(), businessId);
        });
    }

    public ControllerView issueController(UUID owner, String businessId) {
        return database.transaction(connection -> {
            BusinessRow business = requireBusinessOwner(connection, owner, businessId);
            requireOilBusiness(business);
            String serial = "OIL-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
            Instant now = Instant.now();
            try (var insert = connection.prepareStatement("INSERT INTO industrial_controller(serial,business_id,issued_to_uuid,state,issued_at) VALUES(?,?,?,'ISSUED',?)")) {
                insert.setString(1, serial); insert.setString(2, businessId); insert.setString(3, owner.toString());
                insert.setString(4, now.toString()); insert.executeUpdate();
            }
            AuditLog.append(connection, "INDUSTRIAL_CONTROLLER_ISSUED", owner, "BUSINESS", businessId,
                    "serial=" + serial);
            return new ControllerView(serial, businessId, business.name(), owner, "ISSUED", now,
                    null, null, null, null, null, null);
        });
    }

    public ControllerView placeController(UUID actor, String serial, UUID worldId, String worldName,
                                          int x, int y, int z) {
        return database.transaction(connection -> {
            ControllerView controller = controller(connection, serial);
            BusinessRow business = requireBusinessOwner(connection, actor, controller.businessId());
            requireOilBusiness(business);
            if (!"ISSUED".equals(controller.state()) && !"PLACED".equals(controller.state())) {
                throw new IllegalStateException("Контроллер уже привязан к объекту или выведен из эксплуатации");
            }
            if (controller.worldId() != null && !sameLocation(controller, worldId, x, y, z)) {
                throw new IllegalStateException("Этот серийный номер уже размещён в другом месте");
            }
            Instant now = Instant.now();
            try (var occupied = connection.prepareStatement("SELECT serial FROM industrial_controller WHERE world_uuid=? AND x=? AND y=? AND z=? AND state IN ('PLACED','BOUND') AND serial<>?")) {
                occupied.setString(1, worldId.toString()); occupied.setInt(2, x); occupied.setInt(3, y); occupied.setInt(4, z); occupied.setString(5, serial);
                try (var rs = occupied.executeQuery()) { if (rs.next()) throw new IllegalStateException("В этой точке уже зарегистрирован контроллер"); }
            }
            try (var update = connection.prepareStatement("UPDATE industrial_controller SET state='PLACED',world_uuid=?,world_name=?,x=?,y=?,z=?,placed_at=? WHERE serial=? AND state IN ('ISSUED','PLACED')")) {
                update.setString(1, worldId.toString()); update.setString(2, worldName); update.setInt(3, x);
                update.setInt(4, y); update.setInt(5, z); update.setString(6, now.toString()); update.setString(7, serial);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Контроллер уже изменён");
            }
            AuditLog.append(connection, "INDUSTRIAL_CONTROLLER_PLACED", actor, "INDUSTRIAL_CONTROLLER", serial,
                    "world=" + worldName + ";x=" + x + ";y=" + y + ";z=" + z);
            return new ControllerView(serial, controller.businessId(), controller.businessName(), actor, "PLACED",
                    controller.issuedAt(), worldId, worldName, x, y, z, controller.objectId());
        });
    }

    public ControllerView removeController(UUID actor, String serial, boolean administrator) {
        return database.transaction(connection -> {
            ControllerView controller = controller(connection, serial);
            if (!administrator) requireBusinessOwner(connection, actor, controller.businessId());
            Instant now = Instant.now();
            if (controller.objectId() != null) {
                try (var object = connection.prepareStatement("UPDATE industrial_object SET status='MISSING_CONTROLLER',last_error='Контроллер снят или разрушен',updated_at=? WHERE id=? AND status NOT IN ('DECOMMISSIONED','REJECTED','CANCELLED')")) {
                    object.setString(1, now.toString()); object.setString(2, controller.objectId()); object.executeUpdate();
                }
            }
            try (var update = connection.prepareStatement("UPDATE industrial_controller SET state='ISSUED',world_uuid=NULL,world_name=NULL,x=NULL,y=NULL,z=NULL,placed_at=NULL WHERE serial=? AND state IN ('PLACED','BOUND')")) {
                update.setString(1, serial);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Контроллер уже снят или недоступен");
            }
            AuditLog.append(connection, "INDUSTRIAL_CONTROLLER_REMOVED", actor, "INDUSTRIAL_CONTROLLER", serial,
                    "object=" + (controller.objectId() == null ? "" : controller.objectId()));
            return new ControllerView(serial, controller.businessId(), controller.businessName(), controller.issuedTo(),
                    "ISSUED", controller.issuedAt(), null, null, null, null, null, controller.objectId());
        });
    }

    public void restoreControllerBinding(UUID actor, String serial) {
        restoreControllerBinding(actor, serial, false);
    }

    public void restoreControllerBinding(UUID actor, String serial, boolean administrator) {
        database.transaction(connection -> {
            ControllerView controller = controller(connection, serial);
            if (!administrator) requireBusinessOwner(connection, actor, controller.businessId());
            else business(connection, controller.businessId());
            if (controller.objectId() == null || controller.worldId() == null || !"PLACED".equals(controller.state())) {
                throw new IllegalStateException("Разместите контроллер, связанный с объектом");
            }
            try (var update = connection.prepareStatement("UPDATE industrial_controller SET state='BOUND' WHERE serial=? AND state='PLACED'")) {
                update.setString(1, serial); if (update.executeUpdate() != 1) throw new IllegalStateException("Контроллер уже изменён");
            }
            try (var update = connection.prepareStatement("UPDATE industrial_object SET status='PAUSED',last_error='',updated_at=? WHERE id=? AND status='MISSING_CONTROLLER'")) {
                update.setString(1, Instant.now().toString()); update.setString(2, controller.objectId()); update.executeUpdate();
            }
            AuditLog.append(connection, "INDUSTRIAL_CONTROLLER_REBOUND", actor, "INDUSTRIAL_CONTROLLER", serial,
                    "object=" + controller.objectId());
            return null;
        });
    }

    public List<ControllerView> controllers(UUID owner, String businessId) {
        return database.transaction(connection -> {
            requireBusinessOwner(connection, owner, businessId);
            List<ControllerView> result = new ArrayList<>();
            try (var query = connection.prepareStatement("""
                    SELECT c.*,b.name business_name FROM industrial_controller c JOIN business b ON b.id=c.business_id
                    WHERE c.business_id=? ORDER BY c.issued_at DESC
                    """)) {
                query.setString(1, businessId);
                try (var rs = query.executeQuery()) { while (rs.next()) result.add(readController(rs)); }
            }
            return List.copyOf(result);
        });
    }

    public ControllerView controller(UUID actor, String serial, boolean administrator) {
        return database.transaction(connection -> {
            ControllerView value = controller(connection, serial);
            BusinessRow business = business(connection, value.businessId());
            Authority authority = authority(connection, actor);
            boolean official = authority != null && authority.cityId().equals(business.cityId())
                    && (authority.role() == CityRole.MAYOR || authority.role() == CityRole.OFFICIAL);
            if (!administrator && !business.ownerId().equals(actor) && !official) {
                throw new SecurityException("Нет доступа к контроллеру этого предприятия");
            }
            return value;
        });
    }

    public List<ControllerLocation> activeControllerLocations() {
        return database.transaction(connection -> {
            List<ControllerLocation> result = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT serial,world_uuid,world_name,x,y,z FROM industrial_controller WHERE state IN ('PLACED','BOUND') AND world_uuid IS NOT NULL");
                 var rs = query.executeQuery()) {
                while (rs.next()) result.add(new ControllerLocation(rs.getString(1), UUID.fromString(rs.getString(2)),
                        rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getInt(6)));
            }
            return List.copyOf(result);
        });
    }

    public ObjectView applyForObject(UUID owner, String businessId, String controllerSerial, String depositId) {
        return database.transaction(connection -> {
            BusinessRow business = requireBusinessOwner(connection, owner, businessId); requireOilBusiness(business);
            ControllerView controller = controller(connection, controllerSerial);
            if (!controller.businessId().equals(businessId) || !"PLACED".equals(controller.state())) {
                throw new IllegalStateException("Требуется размещённый свободный контроллер этого предприятия");
            }
            DepositRow deposit = deposit(connection, depositId);
            if (!deposit.cityId().equals(business.cityId()) || !"ACTIVE".equals(deposit.status()) || deposit.remaining() <= 0) {
                throw new IllegalStateException("Месторождение недоступно предприятию");
            }
            ensurePlot(connection, depositId, businessId, owner);
            String id = UUID.randomUUID().toString(); Instant now = Instant.now();
            try (var insert = connection.prepareStatement("INSERT INTO industrial_object(id,business_id,deposit_id,controller_serial,status,submitted_at,updated_at) VALUES(?,?,?,?,'DRAFT',?,?)")) {
                insert.setString(1, id); insert.setString(2, businessId); insert.setString(3, depositId);
                insert.setString(4, controllerSerial); insert.setString(5, now.toString()); insert.setString(6, now.toString());
                insert.executeUpdate();
            }
            try (var update = connection.prepareStatement("UPDATE industrial_controller SET state='BOUND',object_id=? WHERE serial=? AND state='PLACED'")) {
                update.setString(1, id); update.setString(2, controllerSerial);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Контроллер уже занят другим объектом");
            }
            AuditLog.append(connection, "INDUSTRIAL_OBJECT_DRAFTED", owner, "INDUSTRIAL_OBJECT", id,
                    "business=" + businessId + ";deposit=" + depositId + ";controller=" + controllerSerial);
            return object(connection, owner, id, true);
        });
    }

    public void submitObject(UUID owner, String objectId) {
        database.transaction(connection -> {
            ObjectRow row = objectRow(connection, objectId);
            requireBusinessOwner(connection, owner, row.businessId());
            if (!"DRAFT".equals(row.status())) throw new IllegalStateException("Подать можно только черновик объекта");
            ControllerView controller = controller(connection, row.controllerSerial());
            if (!"BOUND".equals(controller.state()) || controller.worldId() == null) {
                throw new IllegalStateException("Контроллер объекта отсутствует или не привязан");
            }
            Instant now = Instant.now();
            try (var update = connection.prepareStatement("UPDATE industrial_object SET status='PENDING_INSPECTION',submitted_at=?,last_error='',updated_at=? WHERE id=? AND status='DRAFT'")) {
                update.setString(1, now.toString()); update.setString(2, now.toString()); update.setString(3, objectId);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Черновик уже был изменён");
            }
            AuditLog.append(connection, "INDUSTRIAL_OBJECT_SUBMITTED", owner, "INDUSTRIAL_OBJECT", objectId,
                    "business=" + row.businessId() + ";deposit=" + row.depositId() + ";controller=" + row.controllerSerial());
            return null;
        });
    }

    public void withdrawObjectApplication(UUID owner, String objectId) {
        database.transaction(connection -> {
            ObjectRow row = objectRow(connection, objectId);
            requireBusinessOwner(connection, owner, row.businessId());
            if (!"PENDING_INSPECTION".equals(row.status())) throw new IllegalStateException("Отозвать можно только ожидающую заявку");
            try (var update = connection.prepareStatement("UPDATE industrial_object SET status='CANCELLED',last_error='Заявка отозвана владельцем',updated_at=? WHERE id=? AND status='PENDING_INSPECTION'")) {
                update.setString(1, Instant.now().toString()); update.setString(2, objectId);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Заявка уже изменена");
            }
            try (var controller = connection.prepareStatement("UPDATE industrial_controller SET state='PLACED' WHERE serial=? AND state='BOUND'")) {
                controller.setString(1, row.controllerSerial()); controller.executeUpdate();
            }
            releasePlotIfUnused(connection, row.depositId(), row.businessId(), objectId, Instant.now());
            AuditLog.append(connection, "INDUSTRIAL_OBJECT_CANCELLED", owner, "INDUSTRIAL_OBJECT", objectId,
                    "cancelledByOwner=true");
            return null;
        });
    }

    public void resubmitObject(UUID owner, String objectId) {
        database.transaction(connection -> {
            ObjectRow row = objectRow(connection, objectId); requireBusinessOwner(connection, owner, row.businessId());
            if (!("REJECTED".equals(row.status()) || "CANCELLED".equals(row.status()))) {
                throw new IllegalStateException("Повторно подать можно отклонённый или отозванный объект");
            }
            ControllerView controller = controller(connection, row.controllerSerial());
            if (!"PLACED".equals(controller.state()) || controller.worldId() == null) throw new IllegalStateException("Сначала разместите контроллер");
            ensurePlot(connection, row.depositId(), row.businessId(), owner);
            Instant now = Instant.now();
            try (var update = connection.prepareStatement("UPDATE industrial_object SET status='PENDING_INSPECTION',level=NULL,inspection_note='',inspected_at=NULL,inspected_by=NULL,last_error='',submitted_at=?,updated_at=? WHERE id=?")) {
                update.setString(1, now.toString()); update.setString(2, now.toString()); update.setString(3, objectId); update.executeUpdate();
            }
            try (var update = connection.prepareStatement("UPDATE industrial_controller SET state='BOUND',object_id=? WHERE serial=? AND state='PLACED'")) {
                update.setString(1, objectId); update.setString(2, row.controllerSerial()); update.executeUpdate();
            }
            AuditLog.append(connection, "INDUSTRIAL_OBJECT_RESUBMITTED", owner, "INDUSTRIAL_OBJECT", objectId, "resubmitted=true");
            return null;
        });
    }

    public ObjectView inspect(UUID actor, String objectId, boolean approve, int level,
                              String rawNote, boolean administrator) {
        String note = rawNote == null ? "" : rawNote.strip();
        if (note.length() > 180) throw new IllegalArgumentException("Комментарий проверки: не более 180 символов");
        if (approve) settings.get().level(level);
        return database.transaction(connection -> {
            ObjectRow row = objectRow(connection, objectId);
            BusinessRow business = business(connection, row.businessId());
            if (!administrator) requireOfficial(connection, actor, business.cityId());
            if (!"PENDING_INSPECTION".equals(row.status())) throw new IllegalStateException("Объект уже проверен или отозван");
            Instant now = Instant.now();
            String status = approve ? "PAUSED" : "REJECTED";
            ControllerView controller = controller(connection, row.controllerSerial());
            try (var update = connection.prepareStatement("UPDATE industrial_object SET level=?,status=?,inspection_note=?,inspected_at=?,inspected_by=?,inspection_world_uuid=?,inspection_world_name=?,inspection_x=?,inspection_y=?,inspection_z=?,last_error=?,updated_at=? WHERE id=? AND status='PENDING_INSPECTION'")) {
                if (approve) update.setInt(1, level); else update.setObject(1, null);
                update.setString(2, status); update.setString(3, note); update.setString(4, now.toString());
                update.setString(5, actor.toString());
                update.setString(6, controller.worldId() == null ? null : controller.worldId().toString());
                update.setString(7, controller.worldName()); update.setObject(8, controller.x());
                update.setObject(9, controller.y()); update.setObject(10, controller.z());
                update.setString(11, approve ? "" : "Проверка не пройдена");
                update.setString(12, now.toString()); update.setString(13, objectId);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Решение уже было принято");
            }
            if (!approve) {
                try (var releaseController = connection.prepareStatement("UPDATE industrial_controller SET state='PLACED' WHERE serial=? AND state='BOUND'")) {
                    releaseController.setString(1, row.controllerSerial()); releaseController.executeUpdate();
                }
            }
            AuditLog.append(connection, approve ? "INDUSTRIAL_OBJECT_APPROVED" : "INDUSTRIAL_OBJECT_REJECTED",
                    actor, "INDUSTRIAL_OBJECT", objectId, "level=" + (approve ? level : 0) + ";note=" + note);
            return object(connection, actor, objectId, administrator);
        });
    }

    public void start(UUID owner, String objectId) {
        database.transaction(connection -> {
            ObjectRow row = objectRow(connection, objectId); requireBusinessOwner(connection, owner, row.businessId());
            if (row.level() == null) throw new IllegalStateException("Объект ещё не прошёл инспекцию");
            if ("DECOMMISSIONED".equals(row.status()) || "DEPLETED".equals(row.status())) throw new IllegalStateException("Этот объект нельзя запустить");
            ControllerView controller = controller(connection, row.controllerSerial());
            if (!"BOUND".equals(controller.state()) || controller.worldId() == null) throw new IllegalStateException("Контроллер отсутствует или не привязан");
            if (!hasActiveLicense(connection, row.businessId(), row.level(), Instant.now())) throw new IllegalStateException("Нет действующей лицензии " + licenseType(row.level()));
            DepositRow deposit = deposit(connection, row.depositId());
            if (!"ACTIVE".equals(deposit.status()) || deposit.remaining() <= 0) throw new IllegalStateException("Месторождение исчерпано или недоступно");
            if (row.debtMinor() >= settings.get().debtSuspendMinor()) throw new IllegalStateException("Сначала погасите промышленную задолженность");
            Instant now = Instant.now();
            try (var update = connection.prepareStatement("UPDATE industrial_object SET status='ACTIVE',next_cycle_at=?,last_error='',updated_at=? WHERE id=?")) {
                update.setString(1, now.plusSeconds(settings.get().cycleSeconds()).toString());
                update.setString(2, now.toString()); update.setString(3, objectId); update.executeUpdate();
            }
            AuditLog.append(connection, "INDUSTRIAL_OBJECT_STARTED", owner, "INDUSTRIAL_OBJECT", objectId, "manual=true");
            return null;
        });
    }

    public void pause(UUID owner, String objectId) {
        database.transaction(connection -> {
            ObjectRow row = objectRow(connection, objectId); requireBusinessOwner(connection, owner, row.businessId());
            if (isTerminal(row.status())) throw new IllegalStateException("Завершённый объект нельзя поставить на паузу");
            Instant now = Instant.now();
            try (var update = connection.prepareStatement("UPDATE industrial_object SET status='PAUSED',next_cycle_at=COALESCE(next_cycle_at,?),last_error='Остановлено владельцем',updated_at=? WHERE id=?")) {
                update.setString(1, now.plusSeconds(settings.get().cycleSeconds()).toString());
                update.setString(2, now.toString()); update.setString(3, objectId); update.executeUpdate();
            }
            AuditLog.append(connection, "INDUSTRIAL_OBJECT_PAUSED", owner, "INDUSTRIAL_OBJECT", objectId, "manual=true");
            return null;
        });
    }

    public void decommission(UUID owner, String objectId) {
        database.transaction(connection -> {
            ObjectRow row = objectRow(connection, objectId); requireBusinessOwner(connection, owner, row.businessId());
            if ("DECOMMISSIONED".equals(row.status())) throw new IllegalStateException("Объект уже выведен из эксплуатации");
            Instant now = Instant.now();
            try (var update = connection.prepareStatement("UPDATE industrial_object SET status='DECOMMISSIONED',next_cycle_at=NULL,last_error='',updated_at=? WHERE id=?")) {
                update.setString(1, now.toString()); update.setString(2, objectId); update.executeUpdate();
            }
            try (var controller = connection.prepareStatement("UPDATE industrial_controller SET state='PLACED' WHERE serial=?")) {
                controller.setString(1, row.controllerSerial()); controller.executeUpdate();
            }
            releasePlotIfUnused(connection, row.depositId(), row.businessId(), objectId, now);
            AuditLog.append(connection, "INDUSTRIAL_OBJECT_DECOMMISSIONED", owner, "INDUSTRIAL_OBJECT", objectId,
                    "debtPreserved=" + row.debtMinor());
            return null;
        });
    }

    public List<ObjectView> objects(UUID actor, String businessId, boolean administrator) {
        return database.transaction(connection -> {
            BusinessRow business = business(connection, businessId);
            if (!administrator) {
                Authority authority = authority(connection, actor);
                boolean owner = business.ownerId().equals(actor);
                boolean official = authority != null && authority.cityId().equals(business.cityId())
                        && (authority.role() == CityRole.MAYOR || authority.role() == CityRole.OFFICIAL);
                if (!owner && !official) throw new SecurityException("Промышленные объекты доступны владельцу и городской власти");
            }
            List<ObjectView> result = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT id FROM industrial_object WHERE business_id=? ORDER BY submitted_at DESC")) {
                query.setString(1, businessId); try (var rs = query.executeQuery()) {
                    while (rs.next()) result.add(object(connection, actor, rs.getString(1), administrator));
                }
            }
            return List.copyOf(result);
        });
    }

    public List<ObjectView> pendingInspections(UUID actor, boolean administrator) {
        return database.transaction(connection -> {
            Authority authority = authority(connection, actor);
            if (!administrator && (authority == null || (authority.role() != CityRole.MAYOR && authority.role() != CityRole.OFFICIAL))) {
                throw new SecurityException("Требуется должность городского инспектора");
            }
            String sql = "SELECT o.id FROM industrial_object o JOIN business b ON b.id=o.business_id WHERE o.status='PENDING_INSPECTION'"
                    + (administrator ? "" : " AND b.city_id=?") + " ORDER BY o.submitted_at";
            List<ObjectView> result = new ArrayList<>();
            try (var query = connection.prepareStatement(sql)) {
                if (!administrator) query.setString(1, authority.cityId());
                try (var rs = query.executeQuery()) { while (rs.next()) result.add(object(connection, actor, rs.getString(1), administrator)); }
            }
            return List.copyOf(result);
        });
    }

    public List<ObjectView> diagnosticObjects(UUID actor, boolean administrator) {
        if (!administrator) throw new SecurityException("Требуется право администратора промышленности");
        return database.transaction(connection -> {
            List<ObjectView> result = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT id FROM industrial_object ORDER BY updated_at DESC LIMIT 50");
                 var rs = query.executeQuery()) {
                while (rs.next()) result.add(object(connection, actor, rs.getString(1), true));
            }
            return List.copyOf(result);
        });
    }

    public ObjectView object(UUID actor, String objectId, boolean administrator) {
        return database.transaction(connection -> object(connection, actor, objectId, administrator));
    }

    public CycleBatch processDue(Instant now) {
        if (!settings.get().enabled()) return new CycleBatch(0, 0, 0);
        List<String> ids = database.transaction(connection -> {
            List<String> result = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT id FROM industrial_object WHERE next_cycle_at IS NOT NULL AND next_cycle_at<=? AND status NOT IN ('PENDING_INSPECTION','REJECTED','CANCELLED','DECOMMISSIONED','DEPLETED') ORDER BY next_cycle_at LIMIT 100")) {
                query.setString(1, now.toString()); try (var rs = query.executeQuery()) { while (rs.next()) result.add(rs.getString(1)); }
            }
            return result;
        });
        int cycles = 0; int produced = 0; int failed = 0;
        for (String id : ids) {
            try {
                ObjectBatch batch = database.transaction(connection -> processObject(connection, id, now));
                cycles += batch.cycles(); produced += batch.produced();
            } catch (RuntimeException error) {
                failed++;
                recordProcessingFailure(id, error);
            }
        }
        return new CycleBatch(cycles, produced, failed);
    }

    public List<CycleView> recentCycles(UUID actor, String objectId, boolean administrator, int limit) {
        return database.transaction(connection -> {
            object(connection, actor, objectId, administrator);
            List<CycleView> result = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT * FROM industrial_cycle WHERE object_id=? ORDER BY due_at DESC LIMIT ?")) {
                query.setString(1, objectId); query.setInt(2, Math.max(1, Math.min(50, limit)));
                try (var rs = query.executeQuery()) {
                    while (rs.next()) result.add(new CycleView(rs.getString("id"), objectId, rs.getString("period_key"),
                            Instant.parse(rs.getString("due_at")), rs.getString("state"), rs.getLong("extracted_units"),
                            rs.getLong("gross_minor"), rs.getLong("maintenance_minor"), rs.getLong("lease_minor"),
                            rs.getLong("tax_minor"), rs.getLong("net_minor"), rs.getLong("debt_added_minor"), rs.getString("details")));
                }
            }
            return List.copyOf(result);
        });
    }

    private ObjectBatch processObject(Connection connection, String objectId, Instant now) throws Exception {
        ObjectRow row = objectRow(connection, objectId);
        if (row.nextCycleAt() == null || row.nextCycleAt().isAfter(now) || isTerminal(row.status())) return new ObjectBatch(0, 0);
        long seconds = settings.get().cycleSeconds();
        long due = Math.max(1, Duration.between(row.nextCycleAt(), now).getSeconds() / seconds + 1);
        int count = (int) Math.min(due, Math.max(1, settings.get().maxCatchUpPeriods()));
        int produced = 0;
        Instant cycleAt = row.nextCycleAt();
        for (int i = 0; i < count; i++) {
            if (processPeriod(connection, objectId, cycleAt)) produced++;
            cycleAt = cycleAt.plusSeconds(seconds);
        }
        Instant next = now.plusSeconds(seconds);
        try (var update = connection.prepareStatement("UPDATE industrial_object SET next_cycle_at=?,updated_at=? WHERE id=? AND status NOT IN ('DECOMMISSIONED','DEPLETED')")) {
            update.setString(1, next.toString()); update.setString(2, now.toString()); update.setString(3, objectId); update.executeUpdate();
        }
        return new ObjectBatch(count, produced);
    }

    private boolean processPeriod(Connection connection, String objectId, Instant dueAt) throws Exception {
        IndustrySettings cfg = settings.get();
        ObjectRow object = objectRow(connection, objectId);
        if (object.level() == null || isTerminal(object.status())) return false;
        String periodKey = objectId + ":" + dueAt.getEpochSecond();
        try (var existing = connection.prepareStatement("SELECT extracted_units FROM industrial_cycle WHERE period_key=?")) {
            existing.setString(1, periodKey); try (var rs = existing.executeQuery()) { if (rs.next()) return rs.getLong(1) > 0; }
        }

        BusinessRow business = business(connection, object.businessId());
        DepositRow deposit = deposit(connection, object.depositId());
        ControllerView controller = controller(connection, object.controllerSerial());
        IndustryLevelSettings level = cfg.level(object.level());
        String businessAccount = businessAccount(connection, object.businessId());
        String treasury = cityTreasury(connection, business.cityId());
        String maintenanceSink = ledger.ensureAccount(connection, "SYSTEM", "INDUSTRY_MAINTENANCE", "ESSENTIALS");
        String stateProcurement = ledger.ensureAccount(connection, "SYSTEM", STATE_PROCUREMENT_ACCOUNT, "ESSENTIALS");
        String stateRevenue = ledger.ensureAccount(connection, "SYSTEM", STATE_REVENUE_ACCOUNT, "ESSENTIALS");
        long lease = activeLease(connection, object.depositId(), object.businessId());

        long debt = object.debtMinor();
        long repaidDebt = Math.min(debt, ledger.balance(connection, businessAccount));
        if (repaidDebt > 0) {
            ledger.transfer(connection, periodKey + ":old-debt", businessAccount, maintenanceSink,
                    repaidDebt, "Погашение промышленной задолженности", null);
            debt -= repaidDebt;
        }

        String state = object.status(); String details = ""; long units = 0; long gross = 0; long tax = 0;
        boolean canProduce = !"PAUSED".equals(object.status()) && debt < cfg.debtSuspendMinor();
        if (!"ACTIVE".equals(business.status())) { state = "SUSPENDED_DEBT"; details = "Предприятие не активно"; canProduce = false; }
        else if (!"BOUND".equals(controller.state()) || controller.worldId() == null) { state = "MISSING_CONTROLLER"; details = "Контроллер отсутствует"; canProduce = false; }
        else if (!hasActiveLicense(connection, object.businessId(), object.level(), dueAt)) { state = "SUSPENDED_LICENSE"; details = "Нет действующей лицензии " + licenseType(object.level()); canProduce = false; }
        else if (!"ACTIVE".equals(deposit.status()) || deposit.remaining() <= 0) { state = "DEPLETED"; details = "Месторождение исчерпано"; canProduce = false; }
        else if ("PAUSED".equals(object.status())) { state = "PAUSED"; details = "Остановлено владельцем"; canProduce = false; }

        if (canProduce) {
            long throughputAvailable = Math.max(0, deposit.throughput() - extractedInBucket(connection,
                    object.depositId(), dueAt, cfg.cycleSeconds()));
            units = Math.min(level.unitsPerCycle(), Math.min(deposit.remaining(), throughputAvailable));
            if (units <= 0) {
                state = "WARNING"; details = "Лимит месторождения на период исчерпан";
            } else {
                gross = Math.multiplyExact(units, level.pricePerUnitMinor());
                tax = Math.floorDiv(Math.multiplyExact(gross, STATE_OIL_SALES_TAX_BPS), 10_000L);
                long producerPayment = gross - tax;
                try (var reserve = connection.prepareStatement("UPDATE resource_deposit SET reserve_remaining=reserve_remaining-?,status=CASE WHEN reserve_remaining-?=0 THEN 'DEPLETED' ELSE status END,updated_at=? WHERE id=? AND reserve_remaining>=?")) {
                    reserve.setLong(1, units); reserve.setLong(2, units); reserve.setString(3, Instant.now().toString());
                    reserve.setString(4, object.depositId()); reserve.setLong(5, units);
                    if (reserve.executeUpdate() != 1) throw new IllegalStateException("Запас месторождения изменился во время расчёта");
                }
                if (producerPayment > 0) ledger.transfer(connection, periodKey + ":producer-payment", stateProcurement,
                        businessAccount, producerPayment, "Автоматическая государственная закупка нефти: " + units + " барр.", null);
                if (tax > 0) ledger.transfer(connection, periodKey + ":state-tax", stateProcurement,
                        stateRevenue, tax, "Фиксированный налог 20% с продажи нефти", null);
                state = "ACTIVE"; details = "Добыто и продано государству " + units + " барр.";
            }
        }

        long maintenance = level.maintenanceMinor();
        long debtAdded = 0;
        debtAdded += payOrDebt(connection, periodKey + ":maintenance", businessAccount, maintenanceSink,
                maintenance, "Обслуживание нефтяного объекта");
        debtAdded += payOrDebt(connection, periodKey + ":lease", businessAccount, treasury,
                lease, "Аренда ресурсного участка");
        debt += debtAdded;
        long net = gross - maintenance - lease - tax;

        if (debt >= cfg.debtSuspendMinor()) { state = "SUSPENDED_DEBT"; details = "Задолженность превысила лимит"; }
        else if (debt > 0 && "ACTIVE".equals(state)) { state = "DEBT"; details = "Есть промышленная задолженность"; }
        DepositRow after = deposit(connection, object.depositId());
        if (after.remaining() <= 0) state = "DEPLETED";

        String cycleId = UUID.randomUUID().toString(); Instant created = Instant.now();
        try (var insert = connection.prepareStatement("""
                INSERT INTO industrial_cycle(id,object_id,period_key,due_at,state,extracted_units,gross_minor,
                maintenance_minor,lease_minor,tax_minor,net_minor,debt_added_minor,details,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            insert.setString(1, cycleId); insert.setString(2, objectId); insert.setString(3, periodKey);
            insert.setString(4, dueAt.toString()); insert.setString(5, state); insert.setLong(6, units);
            insert.setLong(7, gross); insert.setLong(8, maintenance); insert.setLong(9, lease); insert.setLong(10, tax);
            insert.setLong(11, net); insert.setLong(12, debtAdded); insert.setString(13, details); insert.setString(14, created.toString());
            insert.executeUpdate();
        }
        try (var update = connection.prepareStatement("UPDATE industrial_object SET status=?,last_cycle_at=?,debt_minor=?,last_error=?,updated_at=? WHERE id=?")) {
            update.setString(1, state); update.setString(2, dueAt.toString()); update.setLong(3, debt);
            update.setString(4, "ACTIVE".equals(state) ? "" : details); update.setString(5, created.toString()); update.setString(6, objectId);
            update.executeUpdate();
        }
        AuditLog.append(connection, "INDUSTRIAL_CYCLE_COMPLETED", null, "INDUSTRIAL_OBJECT", objectId,
                "period=" + periodKey + ";state=" + state + ";units=" + units + ";gross=" + gross + ";debtAdded=" + debtAdded);
        return units > 0;
    }

    private long payOrDebt(Connection connection, String key, String debit, String credit,
                           long amount, String reason) throws Exception {
        if (amount <= 0) return 0;
        if (ledger.balance(connection, debit) < amount) return amount;
        ledger.transfer(connection, key, debit, credit, amount, reason, null);
        return 0;
    }

    private long extractedInBucket(Connection connection, String depositId, Instant dueAt, long seconds) throws Exception {
        long startEpoch = Math.floorDiv(dueAt.getEpochSecond(), seconds) * seconds;
        Instant start = Instant.ofEpochSecond(startEpoch); Instant end = start.plusSeconds(seconds);
        try (var query = connection.prepareStatement("SELECT COALESCE(SUM(c.extracted_units),0) FROM industrial_cycle c JOIN industrial_object o ON o.id=c.object_id WHERE o.deposit_id=? AND c.due_at>=? AND c.due_at<?")) {
            query.setString(1, depositId); query.setString(2, start.toString()); query.setString(3, end.toString());
            try (var rs = query.executeQuery()) { return rs.next() ? rs.getLong(1) : 0; }
        }
    }

    private void recordProcessingFailure(String objectId, RuntimeException error) {
        try {
            database.transaction(connection -> {
                try (var update = connection.prepareStatement("UPDATE industrial_object SET last_error=?,updated_at=? WHERE id=?")) {
                    update.setString(1, rootMessage(error)); update.setString(2, Instant.now().toString()); update.setString(3, objectId); update.executeUpdate();
                }
                AuditLog.append(connection, "INDUSTRIAL_CYCLE_FAILED", null, "INDUSTRIAL_OBJECT", objectId,
                        "error=" + rootMessage(error));
                return null;
            });
        } catch (RuntimeException ignored) {
            // The scheduler reports the original failure count; startup remains available for diagnostics.
        }
    }

    private ObjectView object(Connection connection, UUID actor, String objectId, boolean administrator) throws Exception {
        String sql = """
                SELECT o.*,b.city_id,b.owner_uuid,b.name business_name,d.slug deposit_slug,d.name deposit_name,
                       d.reserve_remaining,d.reserve_total,d.throughput_limit,c.state controller_state,
                       c.world_uuid,c.world_name,c.x,c.y,c.z,p.lease_minor,a.balance_minor business_balance
                FROM industrial_object o JOIN business b ON b.id=o.business_id
                JOIN resource_deposit d ON d.id=o.deposit_id
                JOIN industrial_controller c ON c.serial=o.controller_serial
                LEFT JOIN resource_plot p ON p.id=(SELECT rp.id FROM resource_plot rp WHERE rp.deposit_id=o.deposit_id AND rp.business_id=o.business_id ORDER BY rp.assigned_at DESC LIMIT 1)
                JOIN account a ON a.owner_type='BUSINESS' AND a.owner_id=b.id AND a.currency='ESSENTIALS'
                WHERE o.id=?
                """;
        try (var query = connection.prepareStatement(sql)) {
            query.setString(1, objectId);
            try (var rs = query.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Промышленный объект не найден");
                UUID owner = UUID.fromString(rs.getString("owner_uuid"));
                Authority authority = authority(connection, actor);
                boolean official = authority != null && authority.cityId().equals(rs.getString("city_id"))
                        && (authority.role() == CityRole.MAYOR || authority.role() == CityRole.OFFICIAL);
                if (!administrator && !owner.equals(actor) && !official) throw new SecurityException("Нет доступа к промышленному объекту");
                String levelValue = rs.getString("level");
                String next = rs.getString("next_cycle_at"); String last = rs.getString("last_cycle_at");
                return new ObjectView(rs.getString("id"), rs.getString("business_id"), rs.getString("business_name"),
                        owner, rs.getString("city_id"), rs.getString("deposit_id"), rs.getString("deposit_slug"),
                        rs.getString("deposit_name"), rs.getString("controller_serial"), rs.getString("controller_state"),
                        levelValue == null ? null : rs.getInt("level"), rs.getString("status"), rs.getString("inspection_note"),
                        Instant.parse(rs.getString("submitted_at")), next == null ? null : Instant.parse(next),
                        last == null ? null : Instant.parse(last), rs.getLong("debt_minor"), rs.getString("last_error"),
                        rs.getLong("reserve_remaining"), rs.getLong("reserve_total"), rs.getLong("throughput_limit"),
                        rs.getLong("lease_minor"), rs.getLong("business_balance"), owner.equals(actor), official || administrator);
            }
        }
    }

    private List<DepositView> readDeposits(Connection connection, String cityId, String requestingBusiness) throws Exception {
        String sql = """
                SELECT d.*,p.business_id,p.status plot_status,b.name business_name
                FROM resource_deposit d LEFT JOIN resource_plot p ON p.deposit_id=d.id AND p.status='ACTIVE'
                LEFT JOIN business b ON b.id=p.business_id WHERE 1=1
                """ + (cityId == null ? "" : " AND d.city_id=?") + " ORDER BY d.name COLLATE NOCASE";
        List<DepositView> result = new ArrayList<>();
        try (var query = connection.prepareStatement(sql)) {
            if (cityId != null) query.setString(1, cityId);
            try (var rs = query.executeQuery()) {
                while (rs.next()) {
                    String assigned = rs.getString("business_id");
                    if (requestingBusiness != null && assigned != null && !assigned.equals(requestingBusiness)) continue;
                    result.add(new DepositView(rs.getString("id"), rs.getString("slug"), rs.getString("name"),
                            rs.getString("city_id"), UUID.fromString(rs.getString("world_uuid")), rs.getString("world_name"),
                            rs.getInt("x"), rs.getInt("y"), rs.getInt("z"), rs.getInt("radius"), rs.getLong("reserve_total"),
                            rs.getLong("reserve_remaining"), rs.getLong("throughput_limit"), rs.getString("status"),
                            assigned, rs.getString("business_name")));
                }
            }
        }
        return List.copyOf(result);
    }

    private void ensurePlot(Connection connection, String depositId, String businessId, UUID actor) throws Exception {
        try (var query = connection.prepareStatement("SELECT business_id FROM resource_plot WHERE deposit_id=? AND status='ACTIVE'")) {
            query.setString(1, depositId); try (var rs = query.executeQuery()) {
                if (rs.next()) {
                    if (!businessId.equals(rs.getString(1))) throw new IllegalStateException("Ресурсный участок уже закреплён за другим предприятием");
                    return;
                }
            }
        }
        try (var insert = connection.prepareStatement("INSERT INTO resource_plot(id,deposit_id,business_id,status,lease_minor,assigned_by,assigned_at) VALUES(?,?,?,'ACTIVE',?,?,?)")) {
            insert.setString(1, UUID.randomUUID().toString()); insert.setString(2, depositId); insert.setString(3, businessId);
            insert.setLong(4, settings.get().defaultLeaseMinor()); insert.setString(5, actor.toString()); insert.setString(6, Instant.now().toString()); insert.executeUpdate();
        }
    }

    private void releasePlotIfUnused(Connection connection, String depositId, String businessId,
                                     String excludedObject, Instant now) throws Exception {
        try (var query = connection.prepareStatement("SELECT 1 FROM industrial_object WHERE deposit_id=? AND business_id=? AND id<>? AND status NOT IN ('DECOMMISSIONED','REJECTED','CANCELLED') LIMIT 1")) {
            query.setString(1, depositId); query.setString(2, businessId); query.setString(3, excludedObject);
            try (var rs = query.executeQuery()) { if (rs.next()) return; }
        }
        try (var update = connection.prepareStatement("UPDATE resource_plot SET status='RELEASED',released_at=? WHERE deposit_id=? AND business_id=? AND status='ACTIVE'")) {
            update.setString(1, now.toString()); update.setString(2, depositId); update.setString(3, businessId); update.executeUpdate();
        }
    }

    private long activeLease(Connection connection, String depositId, String businessId) throws Exception {
        try (var query = connection.prepareStatement("SELECT lease_minor FROM resource_plot WHERE deposit_id=? AND business_id=? AND status='ACTIVE'")) {
            query.setString(1, depositId); query.setString(2, businessId); try (var rs = query.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("Активное право на участок не найдено"); return rs.getLong(1);
            }
        }
    }

    private boolean hasActiveLicense(Connection connection, String businessId, int level, Instant at) throws Exception {
        String type = licenseType(level);
        try (var query = connection.prepareStatement("SELECT issued_at,expires_at FROM license WHERE business_id=? AND license_type=? AND status='ACTIVE'")) {
            query.setString(1, businessId); query.setString(2, type);
            try (var rs = query.executeQuery()) {
                if (!rs.next()) return false;
                if (Instant.parse(rs.getString(1)).isAfter(at)) return false;
                String expires = rs.getString(2); return expires == null || Instant.parse(expires).isAfter(at);
            }
        }
    }

    private String licenseType(int level) { return "OIL_EXTRACTION_" + switch (level) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; default -> throw new IllegalArgumentException("Уровень должен быть I–III"); }; }
    private boolean isTerminal(String status) { return "DECOMMISSIONED".equals(status) || "DEPLETED".equals(status) || "REJECTED".equals(status) || "CANCELLED".equals(status); }
    private boolean sameLocation(ControllerView value, UUID worldId, int x, int y, int z) { return worldId.equals(value.worldId()) && value.x() != null && value.x() == x && value.y() == y && value.z() == z; }

    private BusinessRow requireBusinessOwner(Connection connection, UUID owner, String businessId) throws Exception {
        BusinessRow business = business(connection, businessId);
        if (!business.ownerId().equals(owner)) throw new SecurityException("Действие доступно только владельцу предприятия");
        if (!"ACTIVE".equals(business.status())) throw new IllegalStateException("Предприятие должно быть активным");
        return business;
    }

    private void requireOilBusiness(BusinessRow business) {
        if (!"OIL_EXTRACTION".equals(business.activityType())) throw new IllegalStateException("Выберите нефтедобычу как вид деятельности предприятия");
    }

    private BusinessRow business(Connection connection, String id) throws Exception {
        try (var query = connection.prepareStatement("SELECT id,city_id,owner_uuid,name,status,activity_type FROM business WHERE id=?")) {
            query.setString(1, id); try (var rs = query.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Предприятие не найдено");
                return new BusinessRow(rs.getString(1), rs.getString(2), UUID.fromString(rs.getString(3)),
                        rs.getString(4), rs.getString(5), rs.getString(6));
            }
        }
    }

    private ControllerView controller(Connection connection, String serial) throws Exception {
        try (var query = connection.prepareStatement("SELECT c.*,b.name business_name FROM industrial_controller c JOIN business b ON b.id=c.business_id WHERE c.serial=?")) {
            query.setString(1, serial); try (var rs = query.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Контроллер CityCore не найден"); return readController(rs);
            }
        }
    }

    private ControllerView readController(java.sql.ResultSet rs) throws Exception {
        String world = rs.getString("world_uuid");
        return new ControllerView(rs.getString("serial"), rs.getString("business_id"), rs.getString("business_name"),
                UUID.fromString(rs.getString("issued_to_uuid")), rs.getString("state"), Instant.parse(rs.getString("issued_at")),
                world == null ? null : UUID.fromString(world), rs.getString("world_name"),
                world == null ? null : rs.getInt("x"), world == null ? null : rs.getInt("y"), world == null ? null : rs.getInt("z"),
                rs.getString("object_id"));
    }

    private ObjectRow objectRow(Connection connection, String id) throws Exception {
        try (var query = connection.prepareStatement("SELECT id,business_id,deposit_id,controller_serial,level,status,next_cycle_at,debt_minor FROM industrial_object WHERE id=?")) {
            query.setString(1, id); try (var rs = query.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Промышленный объект не найден");
                String level = rs.getString("level"); String next = rs.getString("next_cycle_at");
                return new ObjectRow(id, rs.getString("business_id"), rs.getString("deposit_id"), rs.getString("controller_serial"),
                        level == null ? null : rs.getInt("level"), rs.getString("status"), next == null ? null : Instant.parse(next), rs.getLong("debt_minor"));
            }
        }
    }

    private DepositRow deposit(Connection connection, String id) throws Exception {
        try (var query = connection.prepareStatement("SELECT city_id,reserve_remaining,throughput_limit,status FROM resource_deposit WHERE id=?")) {
            query.setString(1, id); try (var rs = query.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Месторождение не найдено");
                return new DepositRow(id, rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4));
            }
        }
    }

    private Authority authority(Connection connection, UUID player) throws Exception {
        if (player == null) return null;
        try (var query = connection.prepareStatement("SELECT city_id,role FROM citizenship WHERE player_uuid=?")) {
            query.setString(1, player.toString()); try (var rs = query.executeQuery()) {
                return rs.next() ? new Authority(rs.getString(1), CityRole.valueOf(rs.getString(2))) : null;
            }
        }
    }

    private void requireOfficial(Connection connection, UUID player, String cityId) throws Exception {
        Authority value = authority(connection, player);
        if (value == null || !value.cityId().equals(cityId) || (value.role() != CityRole.MAYOR && value.role() != CityRole.OFFICIAL)) {
            throw new SecurityException("Требуется должность инспектора этого города");
        }
    }

    private String cityIdBySlug(Connection connection, String slug) throws Exception {
        try (var query = connection.prepareStatement("SELECT id FROM city WHERE slug=? AND status IN ('ACTIVE','MAYOR_VACANCY')")) {
            query.setString(1, slug); try (var rs = query.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Активный город не найден"); return rs.getString(1);
            }
        }
    }

    private String cityTreasury(Connection connection, String cityId) throws Exception {
        try (var query = connection.prepareStatement("SELECT id FROM account WHERE owner_type='CITY' AND owner_id=? AND currency='ESSENTIALS'")) {
            query.setString(1, cityId); try (var rs = query.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("Казначейский счёт города не найден"); return rs.getString(1);
            }
        }
    }

    private String businessAccount(Connection connection, String businessId) throws Exception {
        try (var query = connection.prepareStatement("SELECT id FROM account WHERE owner_type='BUSINESS' AND owner_id=? AND currency='ESSENTIALS'")) {
            query.setString(1, businessId); try (var rs = query.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("Счёт предприятия не найден"); return rs.getString(1);
            }
        }
    }

    private String normalizeSlug(String raw, String label) {
        String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (!SLUG.matcher(value).matches()) throw new IllegalArgumentException(label + ": 3–24 символа, a-z, 0-9, _ или -");
        return value;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record Authority(String cityId, CityRole role) {}
    private record BusinessRow(String id, String cityId, UUID ownerId, String name, String status, String activityType) {}
    private record ObjectRow(String id, String businessId, String depositId, String controllerSerial,
                             Integer level, String status, Instant nextCycleAt, long debtMinor) {}
    private record DepositRow(String id, String cityId, long remaining, long throughput, String status) {}
    private record ObjectBatch(int cycles, int produced) {}

    public record DepositView(String id, String slug, String name, String cityId, UUID worldId, String worldName,
                              int x, int y, int z, int radius, long reserveTotal, long reserveRemaining,
                              long throughputLimit, String status, String assignedBusinessId, String assignedBusinessName) {}
    public record ControllerView(String serial, String businessId, String businessName, UUID issuedTo,
                                 String state, Instant issuedAt, UUID worldId, String worldName,
                                 Integer x, Integer y, Integer z, String objectId) {}
    public record ControllerLocation(String serial, UUID worldId, String worldName, int x, int y, int z) {}
    public record ObjectView(String id, String businessId, String businessName, UUID ownerId, String cityId,
                             String depositId, String depositSlug, String depositName, String controllerSerial,
                             String controllerState, Integer level, String status, String inspectionNote,
                             Instant submittedAt, Instant nextCycleAt, Instant lastCycleAt, long debtMinor,
                             String lastError, long reserveRemaining, long reserveTotal, long throughputLimit,
                             long leaseMinor, long businessBalanceMinor, boolean owner, boolean official) {}
    public record CycleView(String id, String objectId, String periodKey, Instant dueAt, String state,
                            long extractedUnits, long grossMinor, long maintenanceMinor, long leaseMinor,
                            long taxMinor, long netMinor, long debtAddedMinor, String details) {}
    public record CycleBatch(int cycles, int produced, int failed) {}
}
