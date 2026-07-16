package ru.citycore.economy;

import ru.citycore.db.Database;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.sql.Connection;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public final class InternalLedger {
    private final Database database;
    public InternalLedger(Database database) { this.database = database; }

    public String ensureAccount(String ownerType, String ownerId, String currency) {
        return database.transaction(connection -> ensureAccount(connection, ownerType, ownerId, currency));
    }

    public String ensureAccount(Connection connection, String ownerType, String ownerId, String currency) throws Exception {
        try (var find = connection.prepareStatement("SELECT id FROM account WHERE owner_type=? AND owner_id=? AND currency=?")) {
            find.setString(1, ownerType); find.setString(2, ownerId); find.setString(3, currency);
            try (ResultSet rs = find.executeQuery()) { if (rs.next()) return rs.getString(1); }
        }
        String id = UUID.randomUUID().toString();
        try (var insert = connection.prepareStatement("INSERT INTO account(id,owner_type,owner_id,currency,balance_minor,version) VALUES(?,?,?,?,0,0)")) {
            insert.setString(1, id); insert.setString(2, ownerType); insert.setString(3, ownerId); insert.setString(4, currency);
            insert.executeUpdate();
        }
        return id;
    }

    public LedgerResult transfer(String idempotencyKey, String debitAccount, String creditAccount,
                                 long amountMinor, String reason, UUID actor) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey обязателен");
        if (debitAccount.equals(creditAccount)) throw new IllegalArgumentException("Счета должны различаться");
        if (amountMinor <= 0) throw new IllegalArgumentException("Сумма должна быть положительной");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Причина операции обязательна");
        return database.transaction(connection -> transfer(connection, idempotencyKey, debitAccount, creditAccount,
                amountMinor, reason, actor));
    }

    public LedgerResult transfer(Connection connection, String idempotencyKey, String debitAccount,
                                 String creditAccount, long amountMinor, String reason, UUID actor) throws Exception {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey обязателен");
        if (debitAccount.equals(creditAccount)) throw new IllegalArgumentException("Счета должны различаться");
        if (amountMinor <= 0) throw new IllegalArgumentException("Сумма должна быть положительной");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Причина операции обязательна");
            try (var existing = connection.prepareStatement("SELECT id FROM ledger_entry WHERE idempotency_key=?")) {
                existing.setString(1, idempotencyKey);
                try (ResultSet rs = existing.executeQuery()) { if (rs.next()) return new LedgerResult(rs.getString(1), true); }
            }
            long balance; boolean systemAccount;
            try (var lock = connection.prepareStatement("SELECT balance_minor,owner_type FROM account WHERE id=?")) {
                lock.setString(1, debitAccount);
                try (ResultSet rs = lock.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("Списываемый счёт не найден");
                    balance = rs.getLong(1); systemAccount = "SYSTEM".equals(rs.getString(2));
                }
            }
            if (!systemAccount && balance < amountMinor) throw new InsufficientFundsException(balance, amountMinor);
            ensureExists(connection, creditAccount);

            try (var debit = connection.prepareStatement("UPDATE account SET balance_minor=balance_minor-?,version=version+1 WHERE id=?");
                 var credit = connection.prepareStatement("UPDATE account SET balance_minor=balance_minor+?,version=version+1 WHERE id=?")) {
                debit.setLong(1, amountMinor); debit.setString(2, debitAccount); debit.executeUpdate();
                credit.setLong(1, amountMinor); credit.setString(2, creditAccount); credit.executeUpdate();
            }
            String previousHash = lastHash(connection);
            String id = UUID.randomUUID().toString(); String created = Instant.now().toString();
            String hash = hash(String.join("|", id, idempotencyKey, debitAccount, creditAccount,
                    Long.toString(amountMinor), reason, actor == null ? "" : actor.toString(), created,
                    previousHash == null ? "" : previousHash));
            try (var insert = connection.prepareStatement("INSERT INTO ledger_entry(id,idempotency_key,debit_account,credit_account,amount_minor,reason,actor_uuid,created_at,previous_hash,entry_hash) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, id); insert.setString(2, idempotencyKey); insert.setString(3, debitAccount);
                insert.setString(4, creditAccount); insert.setLong(5, amountMinor); insert.setString(6, reason);
                insert.setString(7, actor == null ? null : actor.toString()); insert.setString(8, created);
                insert.setString(9, previousHash); insert.setString(10, hash); insert.executeUpdate();
            }
            return new LedgerResult(id, false);
    }

    public long balance(Connection connection, String accountId) throws Exception {
        try (var query = connection.prepareStatement("SELECT balance_minor FROM account WHERE id=?")) {
            query.setString(1, accountId);
            try (ResultSet rs = query.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Счёт не найден");
                return rs.getLong(1);
            }
        }
    }

    private void ensureExists(java.sql.Connection connection, String id) throws Exception {
        try (var statement = connection.prepareStatement("SELECT 1 FROM account WHERE id=?")) {
            statement.setString(1, id); try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Счёт зачисления не найден");
            }
        }
    }
    private String lastHash(java.sql.Connection connection) throws Exception {
        try (var statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT entry_hash FROM ledger_entry ORDER BY rowid DESC LIMIT 1")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }
    static String hash(String value) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    public record LedgerResult(String entryId, boolean replayed) {}
}
