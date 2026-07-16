package ru.citycore.city;

import ru.citycore.audit.AuditLog;
import ru.citycore.db.Database;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.time.Duration;
import java.util.function.LongSupplier;

public final class CityService {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9_-]{2,23}");
    private final Database database;
    private final LongSupplier reapplyCooldownSeconds;
    public CityService(Database database) { this(database, () -> 0L); }
    public CityService(Database database, LongSupplier reapplyCooldownSeconds) {
        this.database = database;
        this.reapplyCooldownSeconds = reapplyCooldownSeconds;
    }

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

    public void cancelFoundation(UUID founder, String applicationId) {
        database.transaction(connection -> {
            Instant now = Instant.now();
            try (var update = connection.prepareStatement("UPDATE city_foundation_application SET status='CANCELLED',decided_at=?,decided_by=?,decision_note='Отозвано основателем' WHERE id=? AND founder_uuid=? AND status='PENDING'")) {
                update.setString(1, now.toString());
                update.setString(2, founder.toString());
                update.setString(3, applicationId);
                update.setString(4, founder.toString());
                if (update.executeUpdate() != 1) throw new IllegalArgumentException("Ожидающая заявка на основание не найдена");
            }
            AuditLog.append(connection, "CITY_FOUNDATION_CANCELLED", founder, "CITY_FOUNDATION", applicationId,
                    "cancelledByFounder=true");
            return null;
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
            enforceReapplyCooldown(connection, player);
            String cityId;
            try (var city = connection.prepareStatement("SELECT id FROM city WHERE slug=? AND status IN ('ACTIVE','MAYOR_VACANCY')")) {
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
                appendMembershipEvent(connection, applicant, authority.cityId(), "JOIN", null,
                        CityRole.CITIZEN, actor, "Заявление одобрено");
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
            appendMembershipEvent(connection, member, authority.cityId(), "ROLE_CHANGED", target.role(),
                    newRole, actor, "Изменение роли мэром");
            AuditLog.append(connection, "CITY_ROLE_CHANGED", actor, "PLAYER", member.toString(),
                    "city=" + authority.cityId() + ";role=" + newRole.name());
            return null;
        });
    }

    public void cancelCitizenshipApplication(UUID player, String applicationId) {
        database.transaction(connection -> {
            String now = Instant.now().toString();
            try (var update = connection.prepareStatement("UPDATE citizenship_application SET status='CANCELLED',decided_at=?,decided_by=? WHERE id=? AND player_uuid=? AND status='PENDING'")) {
                update.setString(1, now); update.setString(2, player.toString());
                update.setString(3, applicationId); update.setString(4, player.toString());
                if (update.executeUpdate() != 1) throw new IllegalArgumentException("Ожидающее заявление не найдено");
            }
            AuditLog.append(connection, "CITIZENSHIP_CANCELLED", player, "CITIZENSHIP_APPLICATION",
                    applicationId, "cancelledByApplicant=true");
            return null;
        });
    }

    public MembershipChange leaveCity(UUID player) {
        return database.transaction(connection -> {
            Membership current = membership(connection, player);
            if (current == null) throw new IllegalStateException("У вас нет активного гражданства");
            if (current.role() == CityRole.MAYOR) {
                throw new IllegalStateException("Сначала передайте должность мэра или подайте заявление об отставке");
            }
            if (current.role() == CityRole.OFFICIAL) {
                throw new IllegalStateException("Сначала сложите полномочия городского чиновника");
            }
            try (var delete = connection.prepareStatement("DELETE FROM citizenship WHERE player_uuid=? AND city_id=? AND role=?")) {
                delete.setString(1, player.toString()); delete.setString(2, current.cityId());
                delete.setString(3, CityRole.CITIZEN.name());
                if (delete.executeUpdate() != 1) throw new IllegalStateException("Гражданство уже было изменено");
            }
            appendMembershipEvent(connection, player, current.cityId(), "LEAVE", current.role(), null,
                    player, "Добровольный выход");
            AuditLog.append(connection, "CITIZENSHIP_LEFT", player, "CITY", current.cityId(),
                    "assetsPreserved=true;debtsPreserved=true");
            return new MembershipChange(player, current.cityId(), current.role(), null);
        });
    }

    public MembershipChange resignOfficial(UUID player) {
        return database.transaction(connection -> {
            Membership current = membership(connection, player);
            if (current == null || current.role() != CityRole.OFFICIAL) {
                throw new IllegalStateException("У вас нет должности городского чиновника");
            }
            try (var update = connection.prepareStatement("UPDATE citizenship SET role='CITIZEN' WHERE player_uuid=? AND city_id=? AND role='OFFICIAL'")) {
                update.setString(1, player.toString()); update.setString(2, current.cityId());
                if (update.executeUpdate() != 1) throw new IllegalStateException("Должность уже была изменена");
            }
            appendMembershipEvent(connection, player, current.cityId(), "ROLE_RESIGNED", CityRole.OFFICIAL,
                    CityRole.CITIZEN, player, "Добровольная отставка чиновника");
            AuditLog.append(connection, "CITY_ROLE_RESIGNED", player, "PLAYER", player.toString(),
                    "city=" + current.cityId() + ";role=OFFICIAL");
            return new MembershipChange(player, current.cityId(), CityRole.OFFICIAL, CityRole.CITIZEN);
        });
    }

    public MayorTransfer requestMayorTransfer(UUID mayor, UUID candidate) {
        if (mayor.equals(candidate)) throw new IllegalArgumentException("Нельзя передать должность самому себе");
        return database.transaction(connection -> {
            Membership authority = membership(connection, mayor);
            if (authority == null || authority.role() != CityRole.MAYOR) throw new SecurityException("Требуется должность мэра");
            try (var resignation = connection.prepareStatement("SELECT 1 FROM mayor_resignation_request WHERE city_id=? AND status='PENDING'")) {
                resignation.setString(1, authority.cityId());
                try (var rs = resignation.executeQuery()) {
                    if (rs.next()) throw new IllegalStateException("Сначала отзовите или дождитесь решения по заявлению об отставке");
                }
            }
            Membership target = membership(connection, candidate);
            if (target == null || !target.cityId().equals(authority.cityId()) || target.role() == CityRole.MAYOR) {
                throw new IllegalArgumentException("Кандидат должен состоять в вашем городе");
            }
            String id = UUID.randomUUID().toString(); Instant created = Instant.now();
            try (var insert = connection.prepareStatement("INSERT INTO mayor_transfer_request(id,city_id,from_uuid,to_uuid,status,created_at) VALUES(?,?,?,?,'PENDING',?)")) {
                insert.setString(1, id); insert.setString(2, authority.cityId());
                insert.setString(3, mayor.toString()); insert.setString(4, candidate.toString());
                insert.setString(5, created.toString()); insert.executeUpdate();
            }
            AuditLog.append(connection, "MAYOR_TRANSFER_REQUESTED", mayor, "CITY", authority.cityId(),
                    "request=" + id + ";candidate=" + candidate);
            return new MayorTransfer(id, authority.cityId(), mayor, candidate, "PENDING", created);
        });
    }

    public List<MayorTransfer> incomingMayorTransfers(UUID candidate) {
        return database.transaction(connection -> {
            List<MayorTransfer> result = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT id,city_id,from_uuid,to_uuid,status,created_at FROM mayor_transfer_request WHERE to_uuid=? AND status='PENDING' ORDER BY created_at")) {
                query.setString(1, candidate.toString());
                try (var rs = query.executeQuery()) {
                    while (rs.next()) result.add(readMayorTransfer(rs));
                }
            }
            return List.copyOf(result);
        });
    }

    public List<MayorTransfer> outgoingMayorTransfers(UUID mayor) {
        return database.transaction(connection -> {
            List<MayorTransfer> result = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT id,city_id,from_uuid,to_uuid,status,created_at FROM mayor_transfer_request WHERE from_uuid=? AND status='PENDING' ORDER BY created_at")) {
                query.setString(1, mayor.toString());
                try (var rs = query.executeQuery()) { while (rs.next()) result.add(readMayorTransfer(rs)); }
            }
            return List.copyOf(result);
        });
    }

    public MayorTransfer decideMayorTransfer(UUID candidate, String requestId, boolean accept) {
        return database.transaction(connection -> {
            MayorTransfer transfer;
            try (var query = connection.prepareStatement("SELECT id,city_id,from_uuid,to_uuid,status,created_at FROM mayor_transfer_request WHERE id=? AND to_uuid=? AND status='PENDING'")) {
                query.setString(1, requestId); query.setString(2, candidate.toString());
                try (var rs = query.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("Ожидающее предложение не найдено");
                    transfer = readMayorTransfer(rs);
                }
            }
            Instant now = Instant.now();
            if (accept) {
                Membership source = membership(connection, transfer.fromId());
                Membership target = membership(connection, candidate);
                if (source == null || source.role() != CityRole.MAYOR || !source.cityId().equals(transfer.cityId())) {
                    throw new IllegalStateException("Текущий мэр или состояние города изменились");
                }
                if (target == null || !target.cityId().equals(transfer.cityId())) {
                    throw new IllegalStateException("Кандидат больше не состоит в этом городе");
                }
                try (var oldMayor = connection.prepareStatement("UPDATE citizenship SET role='CITIZEN' WHERE player_uuid=? AND city_id=? AND role='MAYOR'");
                     var newMayor = connection.prepareStatement("UPDATE citizenship SET role='MAYOR' WHERE player_uuid=? AND city_id=? AND role<>'MAYOR'")) {
                    oldMayor.setString(1, transfer.fromId().toString()); oldMayor.setString(2, transfer.cityId());
                    newMayor.setString(1, candidate.toString()); newMayor.setString(2, transfer.cityId());
                    if (oldMayor.executeUpdate() != 1 || newMayor.executeUpdate() != 1) {
                        throw new IllegalStateException("Атомарная передача должности не выполнена");
                    }
                }
                appendMembershipEvent(connection, transfer.fromId(), transfer.cityId(), "MAYOR_TRANSFERRED",
                        CityRole.MAYOR, CityRole.CITIZEN, candidate, "Передача должности принята");
                appendMembershipEvent(connection, candidate, transfer.cityId(), "MAYOR_ACCEPTED",
                        target.role(), CityRole.MAYOR, candidate, "Передача должности принята");
            }
            try (var update = connection.prepareStatement("UPDATE mayor_transfer_request SET status=?,decided_at=?,decided_by=? WHERE id=? AND status='PENDING'")) {
                update.setString(1, accept ? "ACCEPTED" : "REJECTED"); update.setString(2, now.toString());
                update.setString(3, candidate.toString()); update.setString(4, requestId);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Предложение уже обработано");
            }
            AuditLog.append(connection, accept ? "MAYOR_TRANSFER_ACCEPTED" : "MAYOR_TRANSFER_REJECTED",
                    candidate, "CITY", transfer.cityId(), "request=" + requestId + ";from=" + transfer.fromId());
            return new MayorTransfer(transfer.id(), transfer.cityId(), transfer.fromId(), candidate,
                    accept ? "ACCEPTED" : "REJECTED", transfer.createdAt());
        });
    }

    public void cancelMayorTransfer(UUID mayor, String requestId) {
        database.transaction(connection -> {
            try (var update = connection.prepareStatement("UPDATE mayor_transfer_request SET status='CANCELLED',decided_at=?,decided_by=? WHERE id=? AND from_uuid=? AND status='PENDING'")) {
                update.setString(1, Instant.now().toString()); update.setString(2, mayor.toString());
                update.setString(3, requestId); update.setString(4, mayor.toString());
                if (update.executeUpdate() != 1) throw new IllegalArgumentException("Ожидающая передача не найдена");
            }
            AuditLog.append(connection, "MAYOR_TRANSFER_CANCELLED", mayor, "MAYOR_TRANSFER", requestId, "cancelled=true");
            return null;
        });
    }

    public MayorResignation submitMayorResignation(UUID mayor, String rawReason) {
        String reason = rawReason == null ? "" : rawReason.strip();
        if (reason.length() < 5 || reason.length() > 180) throw new IllegalArgumentException("Причина отставки: от 5 до 180 символов");
        return database.transaction(connection -> {
            Membership membership = membership(connection, mayor);
            if (membership == null || membership.role() != CityRole.MAYOR) throw new SecurityException("Требуется должность мэра");
            try (var transfer = connection.prepareStatement("SELECT 1 FROM mayor_transfer_request WHERE city_id=? AND status='PENDING'")) {
                transfer.setString(1, membership.cityId());
                try (var rs = transfer.executeQuery()) {
                    if (rs.next()) throw new IllegalStateException("Сначала завершите или отмените передачу должности");
                }
            }
            String id = UUID.randomUUID().toString(); Instant created = Instant.now();
            try (var insert = connection.prepareStatement("INSERT INTO mayor_resignation_request(id,city_id,mayor_uuid,status,reason,created_at) VALUES(?,?,?,'PENDING',?,?)")) {
                insert.setString(1, id); insert.setString(2, membership.cityId()); insert.setString(3, mayor.toString());
                insert.setString(4, reason); insert.setString(5, created.toString()); insert.executeUpdate();
            }
            AuditLog.append(connection, "MAYOR_RESIGNATION_SUBMITTED", mayor, "CITY", membership.cityId(),
                    "request=" + id + ";reason=" + reason);
            return new MayorResignation(id, membership.cityId(), mayor, "PENDING", reason, created);
        });
    }

    public List<MayorResignation> pendingMayorResignations() {
        return database.transaction(connection -> {
            List<MayorResignation> result = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT id,city_id,mayor_uuid,status,reason,created_at FROM mayor_resignation_request WHERE status='PENDING' ORDER BY created_at");
                 var rs = query.executeQuery()) {
                while (rs.next()) result.add(readMayorResignation(rs));
            }
            return List.copyOf(result);
        });
    }

    public MayorResignation pendingMayorResignation(UUID mayor) {
        return database.transaction(connection -> {
            try (var query = connection.prepareStatement("SELECT id,city_id,mayor_uuid,status,reason,created_at FROM mayor_resignation_request WHERE mayor_uuid=? AND status='PENDING' ORDER BY created_at DESC LIMIT 1")) {
                query.setString(1, mayor.toString());
                try (var rs = query.executeQuery()) { return rs.next() ? readMayorResignation(rs) : null; }
            }
        });
    }

    public void cancelMayorResignation(UUID mayor, String requestId) {
        database.transaction(connection -> {
            try (var update = connection.prepareStatement("UPDATE mayor_resignation_request SET status='CANCELLED',decided_at=?,decided_by=? WHERE id=? AND mayor_uuid=? AND status='PENDING'")) {
                update.setString(1, Instant.now().toString()); update.setString(2, mayor.toString());
                update.setString(3, requestId); update.setString(4, mayor.toString());
                if (update.executeUpdate() != 1) throw new IllegalArgumentException("Ожидающее заявление об отставке не найдено");
            }
            AuditLog.append(connection, "MAYOR_RESIGNATION_CANCELLED", mayor, "MAYOR_RESIGNATION", requestId,
                    "cancelledByMayor=true");
            return null;
        });
    }

    public MayorResignation decideMayorResignation(UUID admin, String requestId, boolean approve) {
        return database.transaction(connection -> {
            MayorResignation request;
            try (var query = connection.prepareStatement("SELECT id,city_id,mayor_uuid,status,reason,created_at FROM mayor_resignation_request WHERE id=? AND status='PENDING'")) {
                query.setString(1, requestId);
                try (var rs = query.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("Ожидающее заявление об отставке не найдено");
                    request = readMayorResignation(rs);
                }
            }
            if (approve) {
                try (var updateRole = connection.prepareStatement("UPDATE citizenship SET role='CITIZEN' WHERE player_uuid=? AND city_id=? AND role='MAYOR'");
                     var updateCity = connection.prepareStatement("UPDATE city SET status='MAYOR_VACANCY' WHERE id=? AND status IN ('ACTIVE','MAYOR_VACANCY')")) {
                    updateRole.setString(1, request.mayorId().toString()); updateRole.setString(2, request.cityId());
                    updateCity.setString(1, request.cityId());
                    if (updateRole.executeUpdate() != 1 || updateCity.executeUpdate() != 1) {
                        throw new IllegalStateException("Мэр или состояние города изменились");
                    }
                }
                appendMembershipEvent(connection, request.mayorId(), request.cityId(), "MAYOR_RESIGNATION_APPROVED",
                        CityRole.MAYOR, CityRole.CITIZEN, admin, request.reason());
            }
            Instant now = Instant.now();
            try (var update = connection.prepareStatement("UPDATE mayor_resignation_request SET status=?,decided_at=?,decided_by=? WHERE id=? AND status='PENDING'")) {
                update.setString(1, approve ? "APPROVED" : "REJECTED"); update.setString(2, now.toString());
                update.setString(3, admin.toString()); update.setString(4, requestId); update.executeUpdate();
            }
            AuditLog.append(connection, approve ? "MAYOR_RESIGNATION_APPROVED" : "MAYOR_RESIGNATION_REJECTED",
                    admin, "CITY", request.cityId(), "request=" + requestId + ";mayor=" + request.mayorId());
            return new MayorResignation(request.id(), request.cityId(), request.mayorId(),
                    approve ? "APPROVED" : "REJECTED", request.reason(), request.createdAt());
        });
    }

    public void appointMayor(UUID admin, String cityId, UUID candidate) {
        database.transaction(connection -> {
            Membership target = membership(connection, candidate);
            if (target == null || !target.cityId().equals(cityId)) throw new IllegalArgumentException("Кандидат не состоит в выбранном городе");
            try (var existing = connection.prepareStatement("SELECT 1 FROM citizenship WHERE city_id=? AND role='MAYOR'")) {
                existing.setString(1, cityId); try (var rs = existing.executeQuery()) {
                    if (rs.next()) throw new IllegalStateException("У города уже есть мэр");
                }
            }
            try (var updateRole = connection.prepareStatement("UPDATE citizenship SET role='MAYOR' WHERE player_uuid=? AND city_id=?");
                 var updateCity = connection.prepareStatement("UPDATE city SET status='ACTIVE' WHERE id=? AND status='MAYOR_VACANCY'")) {
                updateRole.setString(1, candidate.toString()); updateRole.setString(2, cityId);
                updateCity.setString(1, cityId);
                if (updateRole.executeUpdate() != 1 || updateCity.executeUpdate() != 1) throw new IllegalStateException("Назначение не выполнено");
            }
            appendMembershipEvent(connection, candidate, cityId, "MAYOR_APPOINTED", target.role(),
                    CityRole.MAYOR, admin, "Назначение администрацией после вакансии");
            AuditLog.append(connection, "MAYOR_APPOINTED", admin, "CITY", cityId, "candidate=" + candidate);
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
                    WHERE c.status IN ('ACTIVE','MAYOR_VACANCY')
                    GROUP BY c.id,c.slug,c.name,c.status
                    ORDER BY c.name COLLATE NOCASE
                    """); var rs = query.executeQuery()) {
                while (rs.next()) result.add(new CitySummary(rs.getString("id"), rs.getString("slug"),
                        rs.getString("name"), rs.getString("status"), rs.getInt("citizen_count")));
            }
            return List.copyOf(result);
        });
    }

    public List<CitySummary> vacantCities() {
        return database.transaction(connection -> {
            List<CitySummary> result = new ArrayList<>();
            try (var query = connection.prepareStatement("""
                    SELECT c.id,c.slug,c.name,c.status,COUNT(z.player_uuid) citizen_count
                    FROM city c LEFT JOIN citizenship z ON z.city_id=c.id
                    WHERE c.status='MAYOR_VACANCY'
                    GROUP BY c.id,c.slug,c.name,c.status ORDER BY c.name COLLATE NOCASE
                    """); var rs = query.executeQuery()) {
                while (rs.next()) result.add(new CitySummary(rs.getString("id"), rs.getString("slug"),
                        rs.getString("name"), rs.getString("status"), rs.getInt("citizen_count")));
            }
            return List.copyOf(result);
        });
    }

    public List<Member> membersForAdmin(String cityId) {
        return database.transaction(connection -> membersByCity(connection, cityId));
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
            return membersByCity(connection, membership.cityId());
        });
    }

    private List<Member> membersByCity(java.sql.Connection connection, String cityId) throws Exception {
            List<Member> result = new ArrayList<>();
            try (var query = connection.prepareStatement("""
                    SELECT z.player_uuid,p.last_name,z.role,z.joined_at
                    FROM citizenship z JOIN player_profile p ON p.uuid=z.player_uuid
                    WHERE z.city_id=?
                    ORDER BY CASE z.role WHEN 'MAYOR' THEN 0 WHEN 'OFFICIAL' THEN 1 ELSE 2 END,
                             p.last_name COLLATE NOCASE
                    """)) {
                query.setString(1, cityId);
                try (var rs = query.executeQuery()) {
                    while (rs.next()) result.add(new Member(UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("last_name"), CityRole.valueOf(rs.getString("role")),
                            Instant.parse(rs.getString("joined_at"))));
                }
            }
            return List.copyOf(result);
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

    /** Aggregated facts for the dedicated city statistics screen. */
    public CityStats stats(UUID player) {
        return database.transaction(connection -> {
            Membership membership = membership(connection, player);
            if (membership == null) throw new IllegalStateException("Сначала вступите в город");
            int citizens = 0;
            int officials = 0;
            int mayors = 0;
            try (var query = connection.prepareStatement("""
                    SELECT COUNT(*) total,
                           SUM(CASE WHEN role='CITIZEN' THEN 1 ELSE 0 END) citizens,
                           SUM(CASE WHEN role='OFFICIAL' THEN 1 ELSE 0 END) officials,
                           SUM(CASE WHEN role='MAYOR' THEN 1 ELSE 0 END) mayors
                    FROM citizenship WHERE city_id=?
                    """)) {
                query.setString(1, membership.cityId());
                try (var rs = query.executeQuery()) {
                    if (rs.next()) {
                        citizens = rs.getInt("citizens");
                        officials = rs.getInt("officials");
                        mayors = rs.getInt("mayors");
                    }
                }
            }
            int activeBusinesses;
            int pendingBusinesses;
            try (var query = connection.prepareStatement("""
                    SELECT SUM(CASE WHEN status='ACTIVE' THEN 1 ELSE 0 END) active_count,
                           SUM(CASE WHEN status='PENDING' THEN 1 ELSE 0 END) pending_count
                    FROM business WHERE city_id=?
                    """)) {
                query.setString(1, membership.cityId());
                try (var rs = query.executeQuery()) {
                    rs.next();
                    activeBusinesses = rs.getInt("active_count");
                    pendingBusinesses = rs.getInt("pending_count");
                }
            }
            int industrialObjects;
            try (var query = connection.prepareStatement("""
                    SELECT COUNT(*) FROM industrial_object o
                    JOIN business b ON b.id=o.business_id
                    WHERE b.city_id=? AND o.status NOT IN ('REJECTED','CANCELLED','DECOMMISSIONED')
                    """)) {
                query.setString(1, membership.cityId());
                try (var rs = query.executeQuery()) {
                    industrialObjects = rs.next() ? rs.getInt(1) : 0;
                }
            }
            return new CityStats(citizens + officials + mayors, citizens, officials, mayors,
                    activeBusinesses, pendingBusinesses, industrialObjects);
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
        appendMembershipEvent(connection, founder, id, "JOIN", null, CityRole.MAYOR, founder,
                "Первый мэр при основании города");
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

    private void enforceReapplyCooldown(java.sql.Connection connection, UUID player) throws Exception {
        long seconds = Math.max(0L, reapplyCooldownSeconds.getAsLong());
        if (seconds == 0) return;
        try (var query = connection.prepareStatement("SELECT created_at FROM membership_event WHERE player_uuid=? AND event_type='LEAVE' ORDER BY created_at DESC LIMIT 1")) {
            query.setString(1, player.toString());
            try (var rs = query.executeQuery()) {
                if (!rs.next()) return;
                Instant allowed = Instant.parse(rs.getString(1)).plusSeconds(seconds);
                if (allowed.isAfter(Instant.now())) {
                    long remaining = Math.max(1, Duration.between(Instant.now(), allowed).toSeconds());
                    throw new IllegalStateException("Повторное вступление будет доступно через " + humanDuration(remaining) + ".");
                }
            }
        }
    }

    static String humanDuration(long totalSeconds) {
        long safe = Math.max(1, totalSeconds);
        long days = safe / 86_400;
        long hours = (safe % 86_400) / 3_600;
        long minutes = Math.max(1, (safe % 3_600 + 59) / 60);
        List<String> parts = new ArrayList<>();
        if (days > 0) parts.add(days + " д");
        if (hours > 0) parts.add(hours + " ч");
        if (days == 0 && minutes > 0) parts.add(minutes + " мин");
        return String.join(" ", parts);
    }

    private void appendMembershipEvent(java.sql.Connection connection, UUID player, String cityId,
                                       String eventType, CityRole oldRole, CityRole newRole,
                                       UUID actor, String reason) throws Exception {
        try (var insert = connection.prepareStatement("INSERT INTO membership_event(id,player_uuid,city_id,event_type,old_role,new_role,actor_uuid,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1, UUID.randomUUID().toString()); insert.setString(2, player.toString());
            insert.setString(3, cityId); insert.setString(4, eventType);
            insert.setString(5, oldRole == null ? null : oldRole.name());
            insert.setString(6, newRole == null ? null : newRole.name());
            insert.setString(7, actor == null ? null : actor.toString());
            insert.setString(8, reason == null ? "" : reason); insert.setString(9, Instant.now().toString());
            insert.executeUpdate();
        }
    }

    private MayorTransfer readMayorTransfer(java.sql.ResultSet rs) throws Exception {
        return new MayorTransfer(rs.getString("id"), rs.getString("city_id"),
                UUID.fromString(rs.getString("from_uuid")), UUID.fromString(rs.getString("to_uuid")),
                rs.getString("status"), Instant.parse(rs.getString("created_at")));
    }

    private MayorResignation readMayorResignation(java.sql.ResultSet rs) throws Exception {
        return new MayorResignation(rs.getString("id"), rs.getString("city_id"),
                UUID.fromString(rs.getString("mayor_uuid")), rs.getString("status"),
                rs.getString("reason"), Instant.parse(rs.getString("created_at")));
    }

    public record CreatedCity(String id, String slug, String name, String treasuryAccountId) {}
    public record Membership(String cityId, CityRole role) {}
    public record Application(String id, UUID playerId, String playerName, Instant createdAt) {}
    public record CitySummary(String id, String slug, String name, String status, int citizenCount) {}
    public record PlayerApplication(String id, String citySlug, String cityName, String status, Instant createdAt) {}
    public record Member(UUID playerId, String playerName, CityRole role, Instant joinedAt) {}
    public record CityView(String id, String slug, String name, String status, CityRole role, long treasuryMinor) {}
    public record CityStats(int population, int citizens, int officials, int mayors,
                            int activeBusinesses, int pendingBusinesses, int industrialObjects) {}
    public record FoundationApplication(String id, UUID founderId, String founderName, String slug,
                                        String name, String description, String status,
                                        Instant createdAt, Instant decidedAt) {}
    public record FoundationDecision(boolean approved, CreatedCity city, Instant decidedAt) {}
    public record MembershipChange(UUID playerId, String cityId, CityRole oldRole, CityRole newRole) {}
    public record MayorTransfer(String id, String cityId, UUID fromId, UUID toId, String status, Instant createdAt) {}
    public record MayorResignation(String id, String cityId, UUID mayorId, String status, String reason, Instant createdAt) {}
}
