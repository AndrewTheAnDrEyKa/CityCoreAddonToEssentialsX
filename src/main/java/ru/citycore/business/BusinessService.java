package ru.citycore.business;

import ru.citycore.audit.AuditLog;
import ru.citycore.city.CityRole;
import ru.citycore.db.Database;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.text.Normalizer;
import java.util.function.ToIntFunction;

public final class BusinessService {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9_-]{2,23}");
    private final Database database;
    private final ToIntFunction<String> minimumLicenseDays;
    public BusinessService(Database database) { this(database, ignored -> 1); }
    public BusinessService(Database database, ToIntFunction<String> minimumLicenseDays) {
        this.database = database; this.minimumLicenseDays = minimumLicenseDays;
    }

    public BusinessView register(UUID owner, String rawSlug, String rawName) {
        return register(owner, rawSlug, rawName, BusinessActivity.GENERAL.name(), null, "");
    }

    public BusinessView register(UUID owner, String rawSlug, String rawName, String rawActivity,
                                 Integer requestedIndustryLevel, String rawApplicationNote) {
        String slug = rawSlug.toLowerCase(Locale.ROOT).trim(); String name = rawName.trim();
        if (!SLUG.matcher(slug).matches()) throw new IllegalArgumentException("ID бизнеса: 3–24 символа, a-z, 0-9, _ или -");
        if (name.length() < 3 || name.length() > 48) throw new IllegalArgumentException("Название бизнеса: от 3 до 48 символов");
        BusinessActivity activity = BusinessActivity.parse(rawActivity);
        String applicationNote = rawApplicationNote == null ? "" : rawApplicationNote.strip();
        if (activity.questionnaireRequired()) {
            if (requestedIndustryLevel == null || requestedIndustryLevel < 1 || requestedIndustryLevel > 3) {
                throw new IllegalArgumentException("Для нефтедобычи укажите ожидаемый уровень вышки I–III");
            }
            if (applicationNote.length() < 15 || applicationNote.length() > 180) {
                throw new IllegalArgumentException("Описание нефтяного проекта: от 15 до 180 символов");
            }
        } else {
            requestedIndustryLevel = null;
            applicationNote = "";
        }
        Integer requestedLevel = requestedIndustryLevel;
        String note = applicationNote;
        return database.transaction(connection -> {
            Authority membership = authority(connection, owner);
            if (membership == null) throw new IllegalStateException("Для регистрации требуется гражданство");
            String id = UUID.randomUUID().toString(); String now = Instant.now().toString();
            String status = activity.reviewRequired() ? "PENDING" : "ACTIVE";
            try (var insert = connection.prepareStatement("""
                    INSERT INTO business(id,city_id,owner_uuid,slug,name,status,activity_type,created_at,
                                         requested_industry_level,application_note,review_required)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                insert.setString(1, id); insert.setString(2, membership.cityId()); insert.setString(3, owner.toString());
                insert.setString(4, slug); insert.setString(5, name); insert.setString(6, status);
                insert.setString(7, activity.name()); insert.setString(8, now);
                if (requestedLevel == null) insert.setNull(9, java.sql.Types.INTEGER); else insert.setInt(9, requestedLevel);
                insert.setString(10, note); insert.setInt(11, activity.reviewRequired() ? 1 : 0); insert.executeUpdate();
            }
            String accountId = null;
            if (!activity.reviewRequired()) accountId = createBusinessAccount(connection, id);
            AuditLog.append(connection, activity.reviewRequired() ? "BUSINESS_REGISTERED" : "BUSINESS_AUTO_APPROVED",
                    owner, "BUSINESS", id, "city=" + membership.cityId() + ";slug=" + slug
                            + ";activity=" + activity.name() + ";requestedLevel=" + (requestedLevel == null ? "" : requestedLevel));
            return new BusinessView(id, membership.cityId(), owner, slug, name, status, activity.name(), accountId,
                    requestedLevel, note, activity.reviewRequired());
        });
    }

    public void withdrawRegistration(UUID owner, String businessId) {
        database.transaction(connection -> {
            try (var update = connection.prepareStatement("UPDATE business SET status='CANCELLED',decided_at=?,decided_by=? WHERE id=? AND owner_uuid=? AND status='PENDING'")) {
                update.setString(1, Instant.now().toString()); update.setString(2, owner.toString());
                update.setString(3, businessId); update.setString(4, owner.toString());
                if (update.executeUpdate() != 1) throw new IllegalArgumentException("Ожидающая регистрация предприятия не найдена");
            }
            AuditLog.append(connection, "BUSINESS_REGISTRATION_WITHDRAWN", owner, "BUSINESS", businessId,
                    "withdrawnByOwner=true");
            return null;
        });
    }

    public void setActivity(UUID owner, String businessId, String rawActivity) {
        String activity = BusinessActivity.parse(rawActivity).name();
        database.transaction(connection -> {
            try (var update = connection.prepareStatement("UPDATE business SET activity_type=? WHERE id=? AND owner_uuid=? AND status='ACTIVE' AND NOT EXISTS(SELECT 1 FROM industrial_object WHERE business_id=?)")) {
                update.setString(1, activity); update.setString(2, businessId); update.setString(3, owner.toString()); update.setString(4, businessId);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Вид деятельности нельзя изменить после создания промышленного объекта");
            }
            AuditLog.append(connection, "BUSINESS_ACTIVITY_CHANGED", owner, "BUSINESS", businessId,
                    "activity=" + activity);
            return null;
        });
    }

    public void decide(UUID actor, String businessId, boolean approve) {
        database.transaction(connection -> {
            Authority authority = requireOfficial(connection, actor);
            BusinessRow business = business(connection, businessId);
            if (!business.cityId().equals(authority.cityId())) throw new SecurityException("Бизнес относится к другому городу");
            if (business.ownerId().equals(actor)) {
                throw new SecurityException("Нельзя рассматривать собственную заявку на предприятие");
            }
            if (!"PENDING".equals(business.status())) throw new IllegalStateException("Заявка уже рассмотрена");
            String now = Instant.now().toString(); String status = approve ? "ACTIVE" : "REJECTED";
            try (var update = connection.prepareStatement("UPDATE business SET status=?,decided_at=?,decided_by=? WHERE id=? AND status='PENDING'")) {
                update.setString(1, status); update.setString(2, now); update.setString(3, actor.toString()); update.setString(4, businessId);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Статус бизнеса не изменён");
            }
            if (approve) {
                createBusinessAccount(connection, businessId);
            }
            AuditLog.append(connection, approve ? "BUSINESS_APPROVED" : "BUSINESS_REJECTED", actor, "BUSINESS", businessId, "city=" + authority.cityId());
            return null;
        });
    }

    public LicenseView issueLicense(UUID actor, String businessId, String rawType, int days) {
        BusinessLicenseType license = BusinessLicenseType.parse(normalizeLicenseType(rawType));
        String type = license.code();
        int minimum = Math.max(1, minimumLicenseDays.applyAsInt(type));
        if (days < minimum || days > 3650) throw new IllegalArgumentException("Срок разрешения «" + license.displayName() + "»: от " + minimum + " до 3650 дней");
        return database.transaction(connection -> {
            Authority authority = requireOfficial(connection, actor); BusinessRow business = business(connection, businessId);
            if (!business.cityId().equals(authority.cityId())) throw new SecurityException("Бизнес относится к другому городу");
            if (business.ownerId().equals(actor)) throw new SecurityException("Нельзя выдавать лицензию собственному предприятию");
            if (!"ACTIVE".equals(business.status())) throw new IllegalStateException("Лицензировать можно только активный бизнес");
            validateLicenseApplicability(connection, business, license);
            String id = UUID.randomUUID().toString(); Instant issued = Instant.now(); Instant expires = issued.plus(days, ChronoUnit.DAYS);
            try (var insertLicense = connection.prepareStatement("""
                    INSERT INTO license(id,business_id,license_type,status,issued_at,expires_at,issued_by) VALUES(?,?,?,'ACTIVE',?,?,?)
                    ON CONFLICT(business_id,license_type) DO UPDATE SET id=excluded.id,status='ACTIVE',issued_at=excluded.issued_at,expires_at=excluded.expires_at,issued_by=excluded.issued_by,revoked_at=NULL,revoked_by=NULL
                    """)) {
                insertLicense.setString(1, id); insertLicense.setString(2, businessId); insertLicense.setString(3, type);
                insertLicense.setString(4, issued.toString()); insertLicense.setString(5, expires.toString()); insertLicense.setString(6, actor.toString()); insertLicense.executeUpdate();
            }
            AuditLog.append(connection, "LICENSE_ISSUED", actor, "BUSINESS", businessId, "type=" + type + ";expires=" + expires);
            return new LicenseView(id, businessId, type, "ACTIVE", issued, expires);
        });
    }

    public void revokeLicense(UUID actor, String businessId, String rawType) {
        String type = BusinessLicenseType.parse(normalizeLicenseType(rawType)).code();
        database.transaction(connection -> {
            Authority authority = requireOfficial(connection, actor); BusinessRow business = business(connection, businessId);
            if (!business.cityId().equals(authority.cityId())) throw new SecurityException("Бизнес относится к другому городу");
            if (business.ownerId().equals(actor)) throw new SecurityException("Нельзя отзывать лицензию собственного предприятия");
            try (var update = connection.prepareStatement("UPDATE license SET status='REVOKED',revoked_at=?,revoked_by=? WHERE business_id=? AND license_type=? AND status='ACTIVE'")) {
                update.setString(1, Instant.now().toString()); update.setString(2, actor.toString()); update.setString(3, businessId); update.setString(4, type);
                if (update.executeUpdate() != 1) throw new IllegalArgumentException("Активная лицензия не найдена");
            }
            AuditLog.append(connection, "LICENSE_REVOKED", actor, "BUSINESS", businessId, "type=" + type);
            return null;
        });
    }

    public List<BusinessView> list(UUID actor, boolean pendingOnly) {
        return database.transaction(connection -> {
            Authority membership = authority(connection, actor);
            boolean official = membership != null && (membership.role() == CityRole.MAYOR || membership.role() == CityRole.OFFICIAL);
            if (pendingOnly && !official) throw new SecurityException("Очередь регистраций доступна мэру и чиновникам");
            String sql;
            if (pendingOnly) {
                sql = "SELECT b.*,a.id account_id FROM business b LEFT JOIN account a ON a.owner_type='BUSINESS' AND a.owner_id=b.id WHERE b.city_id=? AND b.status='PENDING' AND b.owner_uuid<>? ORDER BY b.created_at";
            } else if (official) {
                sql = "SELECT b.*,a.id account_id FROM business b LEFT JOIN account a ON a.owner_type='BUSINESS' AND a.owner_id=b.id WHERE b.city_id=? AND b.status='ACTIVE' ORDER BY b.created_at";
            } else {
                sql = "SELECT b.*,a.id account_id FROM business b LEFT JOIN account a ON a.owner_type='BUSINESS' AND a.owner_id=b.id WHERE b.owner_uuid=? AND b.status='ACTIVE' ORDER BY b.created_at";
            }
            List<BusinessView> result = new ArrayList<>();
            try (var query = connection.prepareStatement(sql)) {
                query.setString(1, pendingOnly || official ? membership.cityId() : actor.toString());
                if (pendingOnly) query.setString(2, actor.toString());
                try (var rs = query.executeQuery()) {
                    while (rs.next()) result.add(readBusinessView(rs));
                }
            }
            return List.copyOf(result);
        });
    }

    public List<BusinessView> applications(UUID owner) {
        return database.transaction(connection -> {
            List<BusinessView> result = new ArrayList<>();
            try (var query = connection.prepareStatement("""
                    SELECT b.*,a.id account_id FROM business b
                    LEFT JOIN account a ON a.owner_type='BUSINESS' AND a.owner_id=b.id
                    WHERE b.owner_uuid=? AND b.status<>'ACTIVE'
                    ORDER BY CASE b.status WHEN 'PENDING' THEN 0 ELSE 1 END,b.created_at DESC
                    """)) {
                query.setString(1, owner.toString());
                try (var rs = query.executeQuery()) { while (rs.next()) result.add(readBusinessView(rs)); }
            }
            return List.copyOf(result);
        });
    }

    public List<BusinessLicenseType> availableLicenses(UUID actor, String businessId) {
        return database.transaction(connection -> {
            Authority authority = requireOfficial(connection, actor);
            BusinessRow business = business(connection, businessId);
            if (!business.cityId().equals(authority.cityId())) throw new SecurityException("Бизнес относится к другому городу");
            if (!"ACTIVE".equals(business.status())) return List.of();
            if (business.activity() == BusinessActivity.TRADE) return List.of(BusinessLicenseType.TRADE);
            if (business.activity() != BusinessActivity.OIL_EXTRACTION) return List.of();
            List<BusinessLicenseType> result = new ArrayList<>();
            try (var query = connection.prepareStatement("""
                    SELECT DISTINCT level FROM industrial_object
                    WHERE business_id=? AND level IS NOT NULL
                      AND status NOT IN ('DRAFT','PENDING_INSPECTION','REJECTED','CANCELLED','DECOMMISSIONED','DEPLETED')
                    ORDER BY level
                    """)) {
                query.setString(1, businessId);
                try (var rs = query.executeQuery()) { while (rs.next()) result.add(BusinessLicenseType.oil(rs.getInt(1))); }
            }
            return List.copyOf(result);
        });
    }

    public int pendingReviewCount(UUID actor) {
        return database.transaction(connection -> {
            Authority authority = authority(connection, actor);
            if (authority == null || (authority.role() != CityRole.MAYOR && authority.role() != CityRole.OFFICIAL)) return 0;
            try (var query = connection.prepareStatement(
                    "SELECT COUNT(*) FROM business WHERE city_id=? AND status='PENDING' AND review_required=1 AND owner_uuid<>?")) {
                query.setString(1, authority.cityId());
                query.setString(2, actor.toString());
                try (var rs = query.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
            }
        });
    }

    public List<UUID> reviewerIds(String cityId) {
        return database.transaction(connection -> {
            List<UUID> result = new ArrayList<>();
            try (var query = connection.prepareStatement(
                    "SELECT player_uuid FROM citizenship WHERE city_id=? AND role IN ('MAYOR','OFFICIAL')")) {
                query.setString(1, cityId);
                try (var rs = query.executeQuery()) {
                    while (rs.next()) result.add(UUID.fromString(rs.getString(1)));
                }
            }
            return List.copyOf(result);
        });
    }

    public BusinessDetail detail(UUID actor, String businessId) {
        return database.transaction(connection -> {
            Authority authority = authority(connection, actor);
            try (var query = connection.prepareStatement("""
                    SELECT b.id,b.city_id,b.owner_uuid,b.slug,b.name,b.status,b.activity_type,
                           b.requested_industry_level,b.application_note,b.review_required,p.last_name,
                           a.id account_id,a.balance_minor
                    FROM business b
                    JOIN player_profile p ON p.uuid=b.owner_uuid
                    LEFT JOIN account a ON a.owner_type='BUSINESS' AND a.owner_id=b.id
                    WHERE b.id=?
                    """)) {
                query.setString(1, businessId);
                try (var rs = query.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("Предприятие не найдено");
                    UUID ownerId = UUID.fromString(rs.getString("owner_uuid"));
                    boolean owner = actor.equals(ownerId);
                    if (authority != null && !authority.cityId().equals(rs.getString("city_id")) && !owner) {
                        throw new SecurityException("Предприятие относится к другому городу");
                    }
                    boolean official = authority != null && authority.cityId().equals(rs.getString("city_id"))
                            && (authority.role() == CityRole.MAYOR || authority.role() == CityRole.OFFICIAL);
                    if (!owner && !official && authority == null) throw new SecurityException("Предприятие доступно только владельцу и городской власти");
                    if (!official && !owner && !"ACTIVE".equals(rs.getString("status"))) {
                        throw new SecurityException("Неактивная регистрация доступна только владельцу и городской власти");
                    }
                    Long balance = null;
                    if ((owner || official) && rs.getString("account_id") != null) balance = rs.getLong("balance_minor");
                    return new BusinessDetail(rs.getString("id"), rs.getString("city_id"), ownerId,
                            rs.getString("last_name"), rs.getString("slug"), rs.getString("name"),
                            rs.getString("status"), rs.getString("activity_type"), rs.getString("account_id"), balance, owner, official,
                            requestedLevel(rs), rs.getString("application_note"), rs.getInt("review_required") == 1,
                            readLicenses(connection, businessId));
                }
            }
        });
    }

    public List<LicenseCard> licenseRegistry(UUID actor) {
        return database.transaction(connection -> {
            Authority authority = authority(connection, actor);
            boolean official = authority != null && (authority.role() == CityRole.MAYOR || authority.role() == CityRole.OFFICIAL);
            String sql = """
                    SELECT l.id,l.business_id,l.license_type,l.status,l.issued_at,l.expires_at,b.name
                    FROM license l JOIN business b ON b.id=l.business_id
                    WHERE 
                    """ + (official ? "b.city_id=?" : "b.owner_uuid=?") + " ORDER BY l.expires_at,l.license_type";
            List<LicenseCard> result = new ArrayList<>();
            try (var query = connection.prepareStatement(sql)) {
                query.setString(1, official ? authority.cityId() : actor.toString());
                try (var rs = query.executeQuery()) {
                    while (rs.next()) result.add(new LicenseCard(rs.getString("id"), rs.getString("business_id"),
                            rs.getString("name"), rs.getString("license_type"), rs.getString("status"),
                            Instant.parse(rs.getString("issued_at")),
                            rs.getString("expires_at") == null ? null : Instant.parse(rs.getString("expires_at"))));
                }
            }
            return List.copyOf(result);
        });
    }

    public String accountForOwner(UUID owner, String businessId) {
        return database.transaction(connection -> {
            try (var query = connection.prepareStatement("""
                    SELECT a.id FROM business b
                    JOIN account a ON a.owner_type='BUSINESS' AND a.owner_id=b.id AND a.currency='ESSENTIALS'
                    WHERE b.id=? AND b.owner_uuid=? AND b.status IN ('ACTIVE','WARNING','DEBT','SUSPENDED')
                    """)) {
                query.setString(1, businessId); query.setString(2, owner.toString());
                try (var rs = query.executeQuery()) {
                    if (!rs.next()) throw new SecurityException("Пополнять можно только счёт своего действующего предприятия");
                    return rs.getString(1);
                }
            }
        });
    }

    private List<LicenseView> readLicenses(java.sql.Connection connection, String businessId) throws Exception {
        List<LicenseView> result = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT id,license_type,status,issued_at,expires_at FROM license WHERE business_id=? ORDER BY license_type")) {
            query.setString(1, businessId);
            try (var rs = query.executeQuery()) {
                while (rs.next()) result.add(new LicenseView(rs.getString("id"), businessId,
                        rs.getString("license_type"), rs.getString("status"), Instant.parse(rs.getString("issued_at")),
                        rs.getString("expires_at") == null ? null : Instant.parse(rs.getString("expires_at"))));
            }
        }
        return List.copyOf(result);
    }

    private String createBusinessAccount(java.sql.Connection connection, String businessId) throws Exception {
        String id = UUID.randomUUID().toString();
        try (var account = connection.prepareStatement(
                "INSERT INTO account(id,owner_type,owner_id,currency,balance_minor,version) VALUES(?,'BUSINESS',?,'ESSENTIALS',0,0)")) {
            account.setString(1, id); account.setString(2, businessId); account.executeUpdate();
        }
        return id;
    }

    private BusinessView readBusinessView(java.sql.ResultSet rs) throws Exception {
        return new BusinessView(rs.getString("id"), rs.getString("city_id"), UUID.fromString(rs.getString("owner_uuid")),
                rs.getString("slug"), rs.getString("name"), rs.getString("status"), rs.getString("activity_type"),
                rs.getString("account_id"), requestedLevel(rs), rs.getString("application_note"),
                rs.getInt("review_required") == 1);
    }

    private Integer requestedLevel(java.sql.ResultSet rs) throws Exception {
        int value = rs.getInt("requested_industry_level");
        return rs.wasNull() ? null : value;
    }

    private Authority requireOfficial(java.sql.Connection connection, UUID actor) throws Exception {
        Authority value = authority(connection, actor);
        if (value == null || (value.role() != CityRole.MAYOR && value.role() != CityRole.OFFICIAL)) throw new SecurityException("Требуется должность мэра или чиновника");
        return value;
    }
    private Authority authority(java.sql.Connection connection, UUID player) throws Exception {
        try (var query = connection.prepareStatement("SELECT city_id,role FROM citizenship WHERE player_uuid=?")) {
            query.setString(1, player.toString()); try (var rs = query.executeQuery()) {
                return rs.next() ? new Authority(rs.getString(1), CityRole.valueOf(rs.getString(2))) : null;
            }
        }
    }
    private BusinessRow business(java.sql.Connection connection, String id) throws Exception {
        try (var query = connection.prepareStatement("SELECT id,city_id,owner_uuid,status,activity_type FROM business WHERE id=?")) {
            query.setString(1, id); try (var rs = query.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Бизнес не найден");
                return new BusinessRow(rs.getString(1), rs.getString(2), UUID.fromString(rs.getString(3)),
                        rs.getString(4), BusinessActivity.parse(rs.getString(5)));
            }
        }
    }

    private void validateLicenseApplicability(java.sql.Connection connection, BusinessRow business,
                                              BusinessLicenseType license) throws Exception {
        if (business.activity() != license.activity()) {
            throw new IllegalStateException("Разрешение «" + license.displayName() + "» не соответствует направлению предприятия");
        }
        if (license.oilLevel() == null) return;
        try (var query = connection.prepareStatement("""
                SELECT 1 FROM industrial_object
                WHERE business_id=? AND level=?
                  AND status NOT IN ('DRAFT','PENDING_INSPECTION','REJECTED','CANCELLED','DECOMMISSIONED','DEPLETED')
                LIMIT 1
                """)) {
            query.setString(1, business.id()); query.setInt(2, license.oilLevel());
            try (var rs = query.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("Сначала объект должен пройти инспекцию на соответствующий уровень");
            }
        }
    }
    private String normalizeLicenseType(String rawType) {
        String normalized = Normalizer.normalize(rawType == null ? "" : rawType, Normalizer.Form.NFKC)
                .replace("\u200B", "").replace("\uFEFF", "")
                .replaceAll("[\\p{Z}\\s]+", " ")
                .strip().toUpperCase(Locale.ROOT).replace(' ', '_');
        while (normalized.contains("__")) normalized = normalized.replace("__", "_");
        return normalized;
    }
    private record Authority(String cityId, CityRole role) {}
    private record BusinessRow(String id, String cityId, UUID ownerId, String status, BusinessActivity activity) {}
    public record BusinessView(String id, String cityId, UUID ownerId, String slug, String name, String status,
                               String activityType, String accountId, Integer requestedIndustryLevel,
                               String applicationNote, boolean reviewRequired) {}
    public record LicenseView(String id, String businessId, String type, String status, Instant issuedAt, Instant expiresAt) {}
    public record BusinessDetail(String id, String cityId, UUID ownerId, String ownerName, String slug,
                                 String name, String status, String activityType, String accountId, Long balanceMinor,
                                 boolean owner, boolean official, Integer requestedIndustryLevel,
                                 String applicationNote, boolean reviewRequired, List<LicenseView> licenses) {}
    public record LicenseCard(String id, String businessId, String businessName, String type, String status,
                              Instant issuedAt, Instant expiresAt) {}
}
