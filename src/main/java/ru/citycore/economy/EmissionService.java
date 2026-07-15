package ru.citycore.economy;

import ru.citycore.audit.AuditLog;
import ru.citycore.db.Database;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * The only supported money creation path in CityCore. Every issue is prepared first,
 * executed through the hash-chained ledger and then completed together with its audit event.
 */
public final class EmissionService {
    private static final String CURRENCY = "ESSENTIALS";
    private final Database database;
    private final InternalLedger ledger;
    private final String sourceAccount;
    private final LongSupplier maxAmountMinor;

    public EmissionService(Database database, InternalLedger ledger, LongSupplier maxAmountMinor) {
        this.database = database;
        this.ledger = ledger;
        this.maxAmountMinor = maxAmountMinor;
        if (maxAmountMinor.getAsLong() <= 0) throw new IllegalArgumentException("Лимит эмиссии должен быть положительным");
        this.sourceAccount = ledger.ensureAccount("SYSTEM", "MONETARY_ISSUE", CURRENCY);
    }

    public Issue issueToCity(UUID actor, String rawSlug, long amountMinor, String rawReason) {
        if (actor == null) throw new IllegalArgumentException("Автор эмиссии обязателен");
        if (amountMinor <= 0) throw new IllegalArgumentException("Сумма эмиссии должна быть положительной");
        if (amountMinor > maxAmountMinor.getAsLong()) throw new IllegalArgumentException("Сумма превышает установленный лимит эмиссии");
        String slug = rawSlug == null ? "" : rawSlug.trim().toLowerCase(java.util.Locale.ROOT);
        String reason = rawReason == null ? "" : rawReason.trim();
        if (reason.length() < 5 || reason.length() > 180) {
            throw new IllegalArgumentException("Основание эмиссии: от 5 до 180 символов");
        }
        Prepared prepared = prepare(actor, slug, amountMinor, reason);
        try {
            return execute(prepared);
        } catch (RuntimeException error) {
            markFailed(prepared.id(), error);
            throw error;
        }
    }

    /** Recovers only operations interrupted by a process stop; normal failures are marked FAILED. */
    public int recoverIncomplete() {
        List<Prepared> pending = database.transaction(connection -> {
            List<Prepared> result = new ArrayList<>();
            try (var query = connection.prepareStatement("SELECT id,idempotency_key,actor_uuid,target_type,target_id,target_account,amount_minor,reason,created_at FROM monetary_issue WHERE state='PENDING' ORDER BY created_at");
                 var rs = query.executeQuery()) {
                while (rs.next()) result.add(new Prepared(rs.getString("id"), rs.getString("idempotency_key"),
                        UUID.fromString(rs.getString("actor_uuid")), rs.getString("target_type"),
                        rs.getString("target_id"), rs.getString("target_account"), rs.getLong("amount_minor"),
                        rs.getString("reason"), Instant.parse(rs.getString("created_at"))));
            }
            return result;
        });
        int recovered = 0;
        for (Prepared item : pending) {
            execute(item);
            recovered++;
        }
        return recovered;
    }

    public List<Issue> recent(int limit) {
        int safeLimit = Math.max(1, Math.min(50, limit));
        return database.transaction(connection -> {
            List<Issue> result = new ArrayList<>();
            try (var query = connection.prepareStatement("""
                    SELECT m.id,m.actor_uuid,m.target_type,m.target_id,
                           COALESCE(c.name,m.target_id) target_name,m.amount_minor,m.reason,m.state,
                           m.ledger_entry_id,m.created_at,m.updated_at
                    FROM monetary_issue m
                    LEFT JOIN city c ON m.target_type='CITY' AND c.id=m.target_id
                    ORDER BY m.created_at DESC LIMIT ?
                    """)) {
                query.setInt(1, safeLimit);
                try (var rs = query.executeQuery()) {
                    while (rs.next()) result.add(readIssue(rs));
                }
            }
            return List.copyOf(result);
        });
    }

    private Prepared prepare(UUID actor, String slug, long amountMinor, String reason) {
        return database.transaction(connection -> {
            String cityId;
            String targetAccount;
            try (var query = connection.prepareStatement("""
                    SELECT c.id,a.id account_id FROM city c
                    JOIN account a ON a.owner_type='CITY' AND a.owner_id=c.id AND a.currency=?
                    WHERE c.slug=? AND c.status='ACTIVE'
                    """)) {
                query.setString(1, CURRENCY);
                query.setString(2, slug);
                try (var rs = query.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("Активный город или его казна не найдены");
                    cityId = rs.getString("id");
                    targetAccount = rs.getString("account_id");
                }
            }
            String id = UUID.randomUUID().toString();
            String key = "monetary-issue:" + id;
            Instant created = Instant.now();
            try (var insert = connection.prepareStatement("""
                    INSERT INTO monetary_issue(id,idempotency_key,actor_uuid,target_type,target_id,target_account,
                    amount_minor,currency,reason,state,created_at,updated_at)
                    VALUES(?,?,?,?,?,?,?,? ,?,'PENDING',?,?)
                    """)) {
                insert.setString(1, id);
                insert.setString(2, key);
                insert.setString(3, actor.toString());
                insert.setString(4, "CITY");
                insert.setString(5, cityId);
                insert.setString(6, targetAccount);
                insert.setLong(7, amountMinor);
                insert.setString(8, CURRENCY);
                insert.setString(9, reason);
                insert.setString(10, created.toString());
                insert.setString(11, created.toString());
                insert.executeUpdate();
            }
            return new Prepared(id, key, actor, "CITY", cityId, targetAccount, amountMinor, reason, created);
        });
    }

    private Issue execute(Prepared prepared) {
        InternalLedger.LedgerResult result = ledger.transfer(prepared.idempotencyKey(), sourceAccount,
                prepared.targetAccount(), prepared.amountMinor(), "Эмиссия: " + prepared.reason(), prepared.actor());
        return database.transaction(connection -> {
            Instant completed = Instant.now();
            try (var update = connection.prepareStatement("UPDATE monetary_issue SET state='COMPLETED',ledger_entry_id=?,updated_at=? WHERE id=? AND state='PENDING'")) {
                update.setString(1, result.entryId());
                update.setString(2, completed.toString());
                update.setString(3, prepared.id());
                int changed = update.executeUpdate();
                if (changed == 0) {
                    try (var query = connection.prepareStatement("SELECT state FROM monetary_issue WHERE id=?")) {
                        query.setString(1, prepared.id());
                        try (var rs = query.executeQuery()) {
                            if (!rs.next() || !"COMPLETED".equals(rs.getString(1))) {
                                throw new IllegalStateException("Операция эмиссии уже закрыта в другом состоянии");
                            }
                        }
                    }
                } else {
                    AuditLog.append(connection, "MONEY_ISSUED", prepared.actor(), prepared.targetType(),
                            prepared.targetId(), "issue=" + prepared.id() + ";amount=" + prepared.amountMinor()
                                    + ";reason=" + prepared.reason());
                }
            }
            return new Issue(prepared.id(), prepared.actor(), prepared.targetType(), prepared.targetId(),
                    prepared.targetId(), prepared.amountMinor(), prepared.reason(), "COMPLETED",
                    result.entryId(), prepared.createdAt(), completed);
        });
    }

    private void markFailed(String id, RuntimeException error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        if (message.length() > 160) message = message.substring(0, 160);
        String finalMessage = message;
        database.transaction(connection -> {
            try (var update = connection.prepareStatement("UPDATE monetary_issue SET state='FAILED',error=?,updated_at=? WHERE id=? AND state='PENDING'")) {
                update.setString(1, finalMessage);
                update.setString(2, Instant.now().toString());
                update.setString(3, id);
                update.executeUpdate();
            }
            return null;
        });
    }

    private Issue readIssue(java.sql.ResultSet rs) throws Exception {
        return new Issue(rs.getString("id"), UUID.fromString(rs.getString("actor_uuid")),
                rs.getString("target_type"), rs.getString("target_id"), rs.getString("target_name"),
                rs.getLong("amount_minor"), rs.getString("reason"), rs.getString("state"),
                rs.getString("ledger_entry_id"), Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }

    private record Prepared(String id, String idempotencyKey, UUID actor, String targetType,
                            String targetId, String targetAccount, long amountMinor, String reason,
                            Instant createdAt) {}

    public record Issue(String id, UUID actor, String targetType, String targetId, String targetName,
                        long amountMinor, String reason, String state, String ledgerEntryId,
                        Instant createdAt, Instant updatedAt) {}
}
