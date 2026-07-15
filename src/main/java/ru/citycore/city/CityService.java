package ru.citycore.city;

import ru.citycore.audit.AuditLog;
import ru.citycore.db.Database;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;

public final class CityService {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9_-]{2,23}");
    private final Database database;
    public CityService(Database database) { this.database = database; }

    public FoundationApplication submitFoundation(UUID founder, String rawSlug, String rawName, String rawDescription) {
        String slug = validateSlug(rawSlug);
        String name = validateName(rawName);
        String description = rawDescription == null ? "" : rawDescription.trim();
        if (description.length() < 8 || description.length() > 180) {
            throw new IllegalArgumentException("Описание города: от 8 до 180 символов");
        }
        return database.transaction(connection -> {
            ensureProfile(connection, founder);
            if (membership(connection, founder) != null) throw new IllegalStateException("Игрок уже состоит в городе");
            try (var city = connection.prepareStatement("SELECT 1 FROM city WHERE slug=?")) {
                city.setString(1, slug);
                try (var rs = city.executeQuery()) { if (rs.next()) throw new IllegalStateException("Город с таким ID уже существует"); }
            }
            try (var pending = connection.prepareStatement("SELECT 1 FROM city_foundation_application WHERE founder_uuid=? AND status='PENDING'")) {
                pending.setString(1, founder.toString());
                try (var rs = pending.executeQuery()) { if (rs.next()) throw new IllegalStateException("У вас уже есть заявка на основании города"); }
            }
            try (var pending = connection.prepareStatement("SELECT 1 FROM city_foundation_application WHERE slug=? AND status='PENDING'")) {
                pending.setString(1, slug);
                try (var rs = pending.executeQuery()) { if (rs.next()) throw new IllegalStateException("Этот ID уже указан в другой заявке"); }
            }
            String id = UUID.randomUUID().toString();
            Instant created = Instant.now();
            try (var insert = connection.prepareStatement("INSERT INTO city_foundation_application(id,founder_uuid,slug,name,description,status,created_at) VALUES(?,?,?,?,?,'PENDING',?)")) {
                insert.setString(1, id);
                insert.setString(2, founder.toString());
                insert.setString(3, slug);
                insert.setString(4, name);
                insert.setString(5, description);
                insert.setString(6, created.toString());
                insert.executeUpdate();
            }
            AuditLog.append(connection, "CITY_FOUNDATION_SUBMITTED", founder, "CITY_FOUNDATION", id,
                    "slug=" + slug + ";name=" + name);
            return new FoundationApplication(id, founder, profileName(connection, founder), slug, name,
                    description, "PENDING", created, null);
        });
    }

    public FoundationApplication latestFoundation(UUID founder) {
        return database.transaction(connection -> {
            try (var query = connection.prepareStatement("""
                    SELECT f.*,p.last_name FROM city_foundation_application f
                    JOIN player_profile p ON p.uuid=f.founder_uuid
                    WHERE f.founder_uuid=? ORDER BY f.created_at DESC LIMIT 1
                    """)) {
                query.setString(1, founder.toString());
                try (var rs = query.executeQuery()) { return rs.next() ? readFoundation(rs) : null; }
            }
        });
    }

    public List<FoundationApplication> pendingFoundations() {
        return database.transaction(connection -> {
            List<FoundationApplication> result = new ArrayList<>();
            try (var query = connection.prepareStatement("""
                    SELECT f.*,p.last_name FROM city_foundation_application f
                    JOIN player_profile p ON p.uuid=f.founder_uuid
                    WHERE f.status='PENDING' ORDER BY f.created_at
                    """); var rs = query.executeQuery()) {
                while (rs.next()) result.add(readFoundation(rs));
            }
            return List.copyOf(result);
        });
    }

    public FoundationDecision decideFoundation(UUID actor, String applicationId, boolean approve, String rawNote) {
        String note = rawNote == null ? "" : rawNote.trim();
        if (note.length() > 180) throw new IllegalArgumentException("Комментарий решения: не более 180 символов");
        return database.transaction(connection -> {
            FoundationApplication application;
            try (var query = connection.prepareStatement("""
                    SELECT f.*,p.last_name FROM city_foundation_application f
                    JOIN player_profile p ON p.uuid=f.founder_uuid WHERE f.id=? AND f.status='PENDING'
                    """)) {
                query.setString(1, applicationId);
                try (var rs = query.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("Ожидающая заявка на город не найдена");
                    application = readFoundation(rs);
                }
            }
            CreatedCity city = null;
            if (approve) {
                if (membership(connection, application.founderId()) != null) {
                    throw new IllegalStateException("Основатель уже вступил в другой город");
                }
                try (var existing = connection.prepareStatement("SELECT 1 FROM city WHERE slug=?")) {
                    existing.setString(1, application.slug());
                    try (var rs = existing.executeQuery()) { if (rs.next()) throw new IllegalStateException("ID города уже занят"); }
                }
                city = createApproved(connection, application.founderId(), application.slug(), application.name());
            }
            Instant decided = Instant.now();
            try (var update = connection.prepareStatement("UPDATE city_foundation_application SET status=?,decided_at=?,decided_by=?,decision_note=? WHERE id=? AND status='PENDING'")) {
                update.setString(1, approve ? "APPROVED" : "REJECTED");
                update.setString(2, decided.toString());
                update.setString(3, actor.toString());
                update.setString(4, note);
                update.setString(5, applicationId);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Заявка уже обработана");
            }
            AuditLog.append(connection, approve ? "CITY_FOUNDATION_APPROVED" : "CITY_FOUNDATION_REJECTED",
                    actor, "CITY_FOUNDATION", applicationId,
                    "founder=" + application.founderId() + ";slug=" + application.slug() + ";note=" + note);
            return new FoundationDecision(approve, city, decided);
        });
    }

    public String apply(UUID player, String rawSlug) {
        String slug = rawSlug.toLowerCase(Locale.ROOT).trim();
        return database.transaction(connection -> {
            if (membership(connection, player) != null) throw new IllegalStateException("Игрок уже состоит в городе");
            String cityId;
            try (var city = connection.prepareStatement("SELECT id FROM city WHERE slug=? AND status='ACTIVE'")) {
                city.setString(1, slug); try (var rs = city.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("Активный город не найден"); cityId = rs.getString(1);
                }
            }
            String id = UUID.randomUUID().toString(); String now = Instant.now().toString();
            try (var existing = connection.prepareStatement("SELECT status FROM citizenship_application WHERE player_uuid=? AND city_id=?")) {
                existing.setString(1, player.toString()); existing.setString(2, cityId);
                try (var rs = existing.executeQuery()) {
                    if (rs.next() && "PENDING".equals(rs.getString(1))) throw new IllegalStateException("Заявка уже ожидает решения");
                }
            }
            try (var application = connection.prepareStatement("""
                    INSERT INTO citizenship_application(id,player_uuid,city_id,status,created_at) VALUES(?,?,?,'PENDING',?)
                    ON CONFLICT(player_uuid,city_id) DO UPDATE SET id=excluded.id,status='PENDING',created_at=excluded.created_at,decided_at=NULL,decided_by=NULL
                    """)) {
                application.setString(1, id); application.setString(2, player.toString());
                application.setString(3, cityId); application.setString(4, now); application.executeUpdate();
            }
            AuditLog.append(connection, "CITIZENSHIP_APPLIED", player, "CITY", cityId, "application=" + id);
            return id;
        });
    }

    public void decide(UUID actor, UUID applicant, boolean accept) {
        database.transaction(connection -> {
            Membership authority = membership(connection, actor);
            if (authority == null || authority.role() != CityRole.MAYOR) throw new SecurityException("Только мэр может рассматривать заявки");
            String applicationId;
            try (var application = connection.prepareStatement("SELECT id FROM citizenship_application WHERE player_uuid=? AND city_id=? AND status='PENDING'")) {
                application.setString(1, applicant.toString()); application.setString(2, authority.cityId());
                try (var rs = application.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("Ожидающая заявка не найдена"); applicationId = rs.getString(1); }
            }
            String now = Instant.now().toString();
            if (accept) {
                if (membership(connection, applicant) != null) throw new IllegalStateException("Заявитель уже состоит в городе");
                try (var citizen = connection.prepareStatement("INSERT INTO citizenship(player_uuid,city_id,role,joined_at) VALUES(?,?,?,?)")) {
                    citizen.setString(1, applicant.toString()); citizen.setString(2, authority.cityId());
                    citizen.setString(3, CityRole.CITIZEN.name()); citizen.setString(4, now); citizen.executeUpdate();
                }
            }
            try (var update = connection.prepareStatement("UPDATE citizenship_application SET status=?,decided_at=?,decided_by=? WHERE id=? AND status='PENDING'")) {
                update.setString(1, accept ? "ACCEPTED" : "REJECTED"); update.setString(2, now);
                update.setString(3, actor.toString()); update.setString(4, applicationId);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Заявка уже обработана");
            }
            AuditLog.append(connection, accept ? "CITIZENSHIP_ACCEPTED" : "CITIZENSHIP_REJECTED", actor,
                    "PLAYER", applicant.toString(), "city=" + authority.cityId() + ";application=" + applicationId);
            return null;
        });
    }

    public void setRole(UUID actor, UUID member, CityRole newRole) {
        if (newRole == CityRole.MAYOR) throw new IllegalArgumentException("Передача должности мэра требует отдельной процедуры");
        if (actor.equals(member)) throw new IllegalArgumentException("Мэр не может изменить собственную роль");
        database.transaction(connection -> {
            Membership authority = membership(connection, actor);
            if (authority == null || authority.role() != CityRole.MAYOR) throw new SecurityException("Только мэр назначает должности");
            Membership target = membership(connection, member);
            if (target == null || !target.cityId().equals(authority.cityId())) throw new IllegalArgumentException("Игрок не состоит в вашем городе");
            try (var update = connection.prepareStatement("UPDATE citizenship SET role=? WHERE player_uuid=? AND city_id=?")) {
                update.setString(1, newRole.name()); update.setString(2, member.toString()); update.setString(3, authority.cityId());
                if (update.executeUpdate() != 1) throw new IllegalStateException("Роль не изменена");
            }
            AuditLog.append(connection, "CITY_ROLE_CHANGED", actor, "PLAYER", member.toString(),
                    "city=" + authority.cityId() + ";role=" + newRole.name());
            return null;
        });
    }

    public List<Application> pending(UUID actor) {
        return database.transaction(connection -> {
            Membership authority = membership(connection, actor);
            if (authority == null || authority.role() != CityRole.MAYOR) throw new SecurityException("Только мэр видит заявки");
            List<Application> result = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT a.id,a.player_uuid,p.last_name,a.created_at FROM citizenship_application a JOIN player_profile p ON p.uuid=a.player_uuid WHERE a.city_id=? AND a.status='PENDING' ORDER BY a.created_at")) {
                query.setString(1, authority.cityId()); try (var rs = query.executeQuery()) {
                    while (rs.next()) result.add(new Application(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3), Instant.parse(rs.getString(4))));
                }
            }
            return List.copyOf(result);
        });
    }

    public List<CitySummary> activeCities() {
        return database.transaction(connection -> {
            List<CitySummary> result = new ArrayList<>();
            try (var query = connection.prepareStatement("""
                    SELECT c.id,c.slug,c.name,c.status,COUNT(z.player_uuid) citizen_count
                    FROM city c LEFT JOIN citizenship z ON z.city_id=c.id
                    WHERE c.status='ACTIVE'
                    GROUP BY c.id,c.slug,c.name,c.status
                    ORDER BY c.name COLLATE NOCASE
                    """); var rs = query.executeQuery()) {
                while (rs.next()) result.add(new CitySummary(rs.getString("id"), rs.getString("slug"),
                        rs.getString("name"), rs.getString("status"), rs.getInt("citizen_count")));
            }
            return List.copyOf(result);
        });
    }

    public List<PlayerApplication> applications(UUID player) {
        return database.transaction(connection -> {
            List<PlayerApplication> result = new ArrayList<>();
            try (var query = connection.prepareStatement("""
                    SELECT a.id,c.slug,c.name,a.status,a.created_at
                    FROM citizenship_application a JOIN city c ON c.id=a.city_id
                    WHERE a.player_uuid=? ORDER BY a.created_at DESC
                    """)) {
                query.setString(1, player.toString());
                try (var rs = query.executeQuery()) {
                    while (rs.next()) result.add(new PlayerApplication(rs.getString("id"), rs.getString("slug"),
                            rs.getString("name"), rs.getString("status"), Instant.parse(rs.getString("created_at"))));
                }
            }
            return List.copyOf(result);
        });
    }

    public List<Member> members(UUID actor) {
        return database.transaction(connection -> {
            Membership membership = membership(connection, actor);
            if (membership == null) throw new IllegalStateException("Игрок не состоит в городе");
            List<Member> result = new ArrayList<>();
            try (var query = connection.prepareStatement("""
                    SELECT z.player_uuid,p.last_name,z.role,z.joined_at
                    FROM citizenship z JOIN player_profile p ON p.uuid=z.player_uuid
                    WHERE z.city_id=?
                    ORDER BY CASE z.role WHEN 'MAYOR' THEN 0 WHEN 'OFFICIAL' THEN 1 ELSE 2 END,
                             p.last_name COLLATE NOCASE
                    """)) {
                query.setString(1, membership.cityId());
                try (var rs = query.executeQuery()) {
                    while (rs.next()) result.add(new Member(UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("last_name"), CityRole.valueOf(rs.getString("role")),
                            Instant.parse(rs.getString("joined_at"))));
                }
            }
            return List.copyOf(result);
        });
    }

    public CityView view(UUID player) {
        return database.transaction(connection -> {
            Membership membership = membership(connection, player); if (membership == null) return null;
            try (var query = connection.prepareStatement("SELECT c.slug,c.name,c.status,a.balance_minor FROM city c JOIN account a ON a.owner_type='CITY' AND a.owner_id=c.id WHERE c.id=?")) {
                query.setString(1, membership.cityId()); try (var rs = query.executeQuery()) {
                    if (!rs.next()) return null;
                    return new CityView(membership.cityId(), rs.getString(1), rs.getString(2), rs.getString(3), membership.role(), rs.getLong(4));
                }
            }
        });
    }

    public String treasuryAccount(UUID player) {
        return database.transaction(connection -> {
            Membership membership = membership(connection, player);
            if (membership == null) throw new IllegalStateException("Игрок не состоит в городе");
            try (var query = connection.prepareStatement("SELECT id FROM account WHERE owner_type='CITY' AND owner_id=? AND currency='ESSENTIALS'")) {
                query.setString(1, membership.cityId()); try (var rs = query.executeQuery()) {
                    if (!rs.next()) throw new IllegalStateException("Счёт казначейства не найден"); return rs.getString(1);
                }
            }
        });
    }

    private CreatedCity createApproved(java.sql.Connection connection, UUID founder, String slug, String name) throws Exception {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        try (var city = connection.prepareStatement("INSERT INTO city(id,slug,name,founder_uuid,status,created_at) VALUES(?,?,?,?,?,?)")) {
            city.setString(1, id);
            city.setString(2, slug);
            city.setString(3, name);
            city.setString(4, founder.toString());
            city.setString(5, "ACTIVE");
            city.setString(6, now);
            city.executeUpdate();
        }
        try (var citizen = connection.prepareStatement("INSERT INTO citizenship(player_uuid,city_id,role,joined_at) VALUES(?,?,?,?)")) {
            citizen.setString(1, founder.toString());
            citizen.setString(2, id);
            citizen.setString(3, CityRole.MAYOR.name());
            citizen.setString(4, now);
            citizen.executeUpdate();
        }
        String treasury = UUID.randomUUID().toString();
        try (var account = connection.prepareStatement("INSERT INTO account(id,owner_type,owner_id,currency,balance_minor,version) VALUES(?,?,?,?,0,0)")) {
            account.setString(1, treasury);
            account.setString(2, "CITY");
            account.setString(3, id);
            account.setString(4, "ESSENTIALS");
            account.executeUpdate();
        }
        AuditLog.append(connection, "CITY_CREATED", founder, "CITY", id, "slug=" + slug + ";name=" + name);
        return new CreatedCity(id, slug, name, treasury);
    }

    private void ensureProfile(java.sql.Connection connection, UUID player) throws Exception {
        try (var profile = connection.prepareStatement("SELECT 1 FROM player_profile WHERE uuid=?")) {
            profile.setString(1, player.toString());
            try (var rs = profile.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("Профиль игрока ещё не создан");
            }
        }
    }

    private String profileName(java.sql.Connection connection, UUID player) throws Exception {
        try (var profile = connection.prepareStatement("SELECT last_name FROM player_profile WHERE uuid=?")) {
            profile.setString(1, player.toString());
            try (var rs = profile.executeQuery()) { return rs.next() ? rs.getString(1) : player.toString(); }
        }
    }

    private String validateSlug(String rawSlug) {
        String slug = rawSlug == null ? "" : rawSlug.toLowerCase(Locale.ROOT).trim();
        if (!SLUG.matcher(slug).matches()) throw new IllegalArgumentException("ID города: 3–24 символа, a-z, 0-9, _ или -");
        return slug;
    }

    private String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.length() < 3 || name.length() > 32) throw new IllegalArgumentException("Название города: от 3 до 32 символов");
        return name;
    }

    private FoundationApplication readFoundation(java.sql.ResultSet rs) throws Exception {
        String decided = rs.getString("decided_at");
        return new FoundationApplication(rs.getString("id"), UUID.fromString(rs.getString("founder_uuid")),
                rs.getString("last_name"), rs.getString("slug"), rs.getString("name"),
                rs.getString("description"), rs.getString("status"), Instant.parse(rs.getString("created_at")),
                decided == null ? null : Instant.parse(decided));
    }

    private Membership membership(java.sql.Connection connection, UUID player) throws Exception {
        try (var query = connection.prepareStatement("SELECT city_id,role FROM citizenship WHERE player_uuid=?")) {
            query.setString(1, player.toString()); try (var rs = query.executeQuery()) {
                return rs.next() ? new Membership(rs.getString(1), CityRole.valueOf(rs.getString(2))) : null;
            }
        }
    }

    public record CreatedCity(String id, String slug, String name, String treasuryAccountId) {}
    public record Membership(String cityId, CityRole role) {}
    public record Application(String id, UUID playerId, String playerName, Instant createdAt) {}
    public record CitySummary(String id, String slug, String name, String status, int citizenCount) {}
    public record PlayerApplication(String id, String citySlug, String cityName, String status, Instant createdAt) {}
    public record Member(UUID playerId, String playerName, CityRole role, Instant joinedAt) {}
    public record CityView(String id, String slug, String name, String status, CityRole role, long treasuryMinor) {}
    public record FoundationApplication(String id, UUID founderId, String founderName, String slug,
                                        String name, String description, String status,
                                        Instant createdAt, Instant decidedAt) {}
    public record FoundationDecision(boolean approved, CreatedCity city, Instant decidedAt) {}
}
