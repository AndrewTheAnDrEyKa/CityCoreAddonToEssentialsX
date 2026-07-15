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

public final class BusinessService {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9_-]{2,23}");
    private static final Pattern LICENSE_TYPE = Pattern.compile("[A-Z0-9_]{3,32}");
    private final Database database;
    public BusinessService(Database database) { this.database = database; }

    public BusinessView register(UUID owner, String rawSlug, String rawName) {
        String slug = rawSlug.toLowerCase(Locale.ROOT).trim(); String name = rawName.trim();
        if (!SLUG.matcher(slug).matches()) throw new IllegalArgumentException("ID бизнеса: 3–24 символа, a-z, 0-9, _ или -");
        if (name.length() < 3 || name.length() > 48) throw new IllegalArgumentException("Название бизнеса: от 3 до 48 символов");
        return database.transaction(connection -> {
            Authority membership = authority(connection, owner);
            if (membership == null) throw new IllegalStateException("Для регистрации требуется гражданство");
            String id = UUID.randomUUID().toString(); String now = Instant.now().toString();
            try (var insert = connection.prepareStatement("INSERT INTO business(id,city_id,owner_uuid,slug,name,status,created_at) VALUES(?,?,?,?,?,'PENDING',?)")) {
                insert.setString(1, id); insert.setString(2, membership.cityId()); insert.setString(3, owner.toString());
                insert.setString(4, slug); insert.setString(5, name); insert.setString(6, now); insert.executeUpdate();
            }
            AuditLog.append(connection, "BUSINESS_REGISTERED", owner, "BUSINESS", id, "city=" + membership.cityId() + ";slug=" + slug);
            return new BusinessView(id, membership.cityId(), owner, slug, name, "PENDING", null);
        });
    }

    public void decide(UUID actor, String businessId, boolean approve) {
        database.transaction(connection -> {
            Authority authority = requireOfficial(connection, actor);
            BusinessRow business = business(connection, businessId);
            if (!business.cityId().equals(authority.cityId())) throw new SecurityException("Бизнес относится к другому городу");
            if (!"PENDING".equals(business.status())) throw new IllegalStateException("Заявка уже рассмотрена");
            String now = Instant.now().toString(); String status = approve ? "ACTIVE" : "REJECTED";
            try (var update = connection.prepareStatement("UPDATE business SET status=?,decided_at=?,decided_by=? WHERE id=? AND status='PENDING'")) {
                update.setString(1, status); update.setString(2, now); update.setString(3, actor.toString()); update.setString(4, businessId);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Статус бизнеса не изменён");
            }
            if (approve) {
                try (var account = connection.prepareStatement("INSERT INTO account(id,owner_type,owner_id,currency,balance_minor,version) VALUES(?,?,?,?,0,0)")) {
                    account.setString(1, UUID.randomUUID().toString()); account.setString(2, "BUSINESS");
                    account.setString(3, businessId); account.setString(4, "ESSENTIALS"); account.executeUpdate();
                }
            }
            AuditLog.append(connection, approve ? "BUSINESS_APPROVED" : "BUSINESS_REJECTED", actor, "BUSINESS", businessId, "city=" + authority.cityId());
            return null;
        });
    }

    public LicenseView issueLicense(UUID actor, String businessId, String rawType, int days) {
        String type = rawType.toUpperCase(Locale.ROOT);
        if (!LICENSE_TYPE.matcher(type).matches()) throw new IllegalArgumentException("Тип лицензии: 3–32 символа, A-Z, 0-9 или _");
        if (days < 1 || days > 3650) throw new IllegalArgumentException("Срок лицензии: от 1 до 3650 дней");
        return database.transaction(connection -> {
            Authority authority = requireOfficial(connection, actor); BusinessRow business = business(connection, businessId);
            if (!business.cityId().equals(authority.cityId())) throw new SecurityException("Бизнес относится к другому городу");
            if (!"ACTIVE".equals(business.status())) throw new IllegalStateException("Лицензировать можно только активный бизнес");
            String id = UUID.randomUUID().toString(); Instant issued = Instant.now(); Instant expires = issued.plus(days, ChronoUnit.DAYS);
            try (var license = connection.prepareStatement("""
                    INSERT INTO license(id,business_id,license_type,status,issued_at,expires_at,issued_by) VALUES(?,?,?,'ACTIVE',?,?,?)
                    ON CONFLICT(business_id,license_type) DO UPDATE SET id=excluded.id,status='ACTIVE',issued_at=excluded.issued_at,expires_at=excluded.expires_at,issued_by=excluded.issued_by,revoked_at=NULL,revoked_by=NULL
                    """)) {
                license.setString(1, id); license.setString(2, businessId); license.setString(3, type);
                license.setString(4, issued.toString()); license.setString(5, expires.toString()); license.setString(6, actor.toString()); license.executeUpdate();
            }
            AuditLog.append(connection, "LICENSE_ISSUED", actor, "BUSINESS", businessId, "type=" + type + ";expires=" + expires);
            return new LicenseView(id, businessId, type, "ACTIVE", issued, expires);
        });
    }

    public void revokeLicense(UUID actor, String businessId, String rawType) {
        String type = rawType.toUpperCase(Locale.ROOT);
        database.transaction(connection -> {
            Authority authority = requireOfficial(connection, actor); BusinessRow business = business(connection, businessId);
            if (!business.cityId().equals(authority.cityId())) throw new SecurityException("Бизнес относится к другому городу");
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
            Authority membership = authority(connection, actor); if (membership == null) return List.of();
            String sql = "SELECT b.*,a.id account_id FROM business b LEFT JOIN account a ON a.owner_type='BUSINESS' AND a.owner_id=b.id WHERE b.city_id=?" + (pendingOnly ? " AND b.status='PENDING'" : "") + " ORDER BY b.created_at";
            List<BusinessView> result = new ArrayList<>();
            try (var query = connection.prepareStatement(sql)) {
                query.setString(1, membership.cityId()); try (var rs = query.executeQuery()) {
                    while (rs.next()) result.add(new BusinessView(rs.getString("id"), rs.getString("city_id"), UUID.fromString(rs.getString("owner_uuid")), rs.getString("slug"), rs.getString("name"), rs.getString("status"), rs.getString("account_id")));
                }
            }
            return List.copyOf(result);
        });
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
        try (var query = connection.prepareStatement("SELECT city_id,status FROM business WHERE id=?")) {
            query.setString(1, id); try (var rs = query.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Бизнес не найден"); return new BusinessRow(rs.getString(1), rs.getString(2));
            }
        }
    }
    private record Authority(String cityId, CityRole role) {}
    private record BusinessRow(String cityId, String status) {}
    public record BusinessView(String id, String cityId, UUID ownerId, String slug, String name, String status, String accountId) {}
    public record LicenseView(String id, String businessId, String type, String status, Instant issuedAt, Instant expiresAt) {}
}
