package ru.citycore.economy;

import ru.citycore.db.Database;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;

public final class VaultTransferRepository {
    private final Database database;
    public VaultTransferRepository(Database database) { this.database = database; }

    public Transfer prepare(UUID player, String targetAccount, long amount, long balanceBefore) {
        if (amount <= 0) throw new IllegalArgumentException("Сумма должна быть положительной");
        return database.transaction(connection -> {
            String id = UUID.randomUUID().toString(); String key = "vault-deposit:" + id; String now = Instant.now().toString();
            try (var insert = connection.prepareStatement("INSERT INTO vault_transfer(id,idempotency_key,player_uuid,target_account,amount_minor,direction,state,vault_balance_before_minor,created_at,updated_at) VALUES(?,?,?,?,?,'DEPOSIT','PREPARED',?,?,?)")) {
                insert.setString(1, id); insert.setString(2, key); insert.setString(3, player.toString()); insert.setString(4, targetAccount);
                insert.setLong(5, amount); insert.setLong(6, balanceBefore); insert.setString(7, now); insert.setString(8, now); insert.executeUpdate();
            }
            return new Transfer(id, key, player, targetAccount, amount, "PREPARED");
        });
    }

    public void transition(String id, String expected, String target, String error) {
        database.transaction(connection -> {
            try (var update = connection.prepareStatement("UPDATE vault_transfer SET state=?,error=?,updated_at=? WHERE id=? AND state=?")) {
                update.setString(1, target); update.setString(2, error); update.setString(3, Instant.now().toString());
                update.setString(4, id); update.setString(5, expected);
                if (update.executeUpdate() != 1) throw new IllegalStateException("Недопустимый переход операции " + id + ": " + expected + " → " + target);
            }
            return null;
        });
    }

    public List<Transfer> incomplete() {
        return database.transaction(connection -> {
            List<Transfer> result = new ArrayList<>();
            try (var query = connection.createStatement(); var rs = query.executeQuery("SELECT id,idempotency_key,player_uuid,target_account,amount_minor,state FROM vault_transfer WHERE state NOT IN ('COMPLETED','FAILED','MANUAL_REVIEW') ORDER BY created_at")) {
                while (rs.next()) result.add(new Transfer(rs.getString(1), rs.getString(2), UUID.fromString(rs.getString(3)), rs.getString(4), rs.getLong(5), rs.getString(6)));
            }
            return List.copyOf(result);
        });
    }

    public record Transfer(String id, String idempotencyKey, UUID playerId, String targetAccount, long amountMinor, String state) {}
}
