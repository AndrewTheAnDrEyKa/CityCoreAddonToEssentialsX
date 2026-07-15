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

    public CreatedCity create(UUID founder, String rawSlug, String rawName) {
        String slug = rawSlug.toLowerCase(Locale.ROOT).trim(); String name = rawName.trim();
        if (!SLUG.matcher(slug).matches()) throw new IllegalArgumentException("ID города: 3–24 символа, a-z, 0-9, _ или -");
        if (name.length() < 3 || name.length() > 32) throw new IllegalArgumentException("Название города: от 3 до 32 символов");
        return database.transaction(connection -> {
            try (var profile = connection.prepareStatement("SELECT 1 FROM player_profile WHERE uuid=?")) {
                profile.setString(1, founder.toString());
                try (var rs = profile.executeQuery()) { if (!rs.next()) throw new IllegalStateException("Профиль игрока ещё не создан"); }
            }
            try (var citizenship = connection.prepareStatement("SELECT 1 FROM citizenship WHERE player_uuid=?")) {
                citizenship.setString(1, founder.toString());
                try (var rs = citizenship.executeQuery()) { if (rs.next()) throw new IllegalStateException("Игрок уже состоит в городе"); }
            }
            String id = UUID.randomUUID().toString(); String now = Instant.now().toString();
            try (var city = connection.prepareStatement("INSERT INTO city(id,slug,name,founder_uuid,status,created_at) VALUES(?,?,?,?,?,?)")) {
                city.setString(1, id); city.setString(2, slug); city.setString(3, name);
                city.setString(4, founder.toString()); city.setString(5, "ACTIVE"); city.setString(6, now); city.executeUpdate();
            }
            try (var citizen = connection.prepareStatement("INSERT INTO citizenship(player_uuid,city_id,role,joined_at) VALUES(?,?,?,?)")) {
                citizen.setString(1, founder.toString()); citizen.setString(2, id);
                citizen.setString(3, CityRole.MAYOR.name()); citizen.setString(4, now); citizen.executeUpdate();
            }
            String treasury = UUID.randomUUID().toString();
            try (var account = connection.prepareStatement("INSERT INTO account(id,owner_type,owner_id,currency,balance_minor,version) VALUES(?,?,?,?,0,0)")) {
                account.setString(1, treasury); account.setString(2, "CITY"); account.setString(3, id); account.setString(4, "ESSENTIALS"); account.executeUpdate();
            }
            AuditLog.append(connection, "CITY_CREATED", founder, "CITY", id, "slug=" + slug + ";name=" + name);
            return new CreatedCity(id, slug, name, treasury);
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
}
