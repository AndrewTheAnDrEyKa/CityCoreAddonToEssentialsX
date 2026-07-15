package ru.citycore.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

public final class Migrations {
    private static final List<String> V1 = List.of(
            "CREATE TABLE IF NOT EXISTS schema_history(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS player_profile(uuid TEXT PRIMARY KEY, last_name TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS city(id TEXT PRIMARY KEY, slug TEXT NOT NULL UNIQUE, name TEXT NOT NULL, founder_uuid TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, FOREIGN KEY(founder_uuid) REFERENCES player_profile(uuid))",
            "CREATE TABLE IF NOT EXISTS citizenship(player_uuid TEXT NOT NULL, city_id TEXT NOT NULL, role TEXT NOT NULL, joined_at TEXT NOT NULL, PRIMARY KEY(player_uuid, city_id), FOREIGN KEY(player_uuid) REFERENCES player_profile(uuid), FOREIGN KEY(city_id) REFERENCES city(id))",
            "CREATE TABLE IF NOT EXISTS account(id TEXT PRIMARY KEY, owner_type TEXT NOT NULL, owner_id TEXT NOT NULL, currency TEXT NOT NULL, balance_minor INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 0, UNIQUE(owner_type, owner_id, currency))",
            "CREATE TABLE IF NOT EXISTS ledger_entry(id TEXT PRIMARY KEY, idempotency_key TEXT NOT NULL UNIQUE, debit_account TEXT NOT NULL, credit_account TEXT NOT NULL, amount_minor INTEGER NOT NULL CHECK(amount_minor > 0), reason TEXT NOT NULL, actor_uuid TEXT, created_at TEXT NOT NULL, previous_hash TEXT, entry_hash TEXT NOT NULL, FOREIGN KEY(debit_account) REFERENCES account(id), FOREIGN KEY(credit_account) REFERENCES account(id))",
            "CREATE INDEX IF NOT EXISTS idx_citizenship_city ON citizenship(city_id)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_citizenship_player ON citizenship(player_uuid)",
            "CREATE INDEX IF NOT EXISTS idx_ledger_created ON ledger_entry(created_at)"
    );

    private Migrations() {}

    public static void apply(Database database) {
        database.transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(V1.getFirst());
                if (currentVersion(statement) < 1) {
                    for (int i = 1; i < V1.size(); i++) statement.execute(V1.get(i));
                    statement.executeUpdate("INSERT INTO schema_history(version, applied_at) VALUES(1, '" + Instant.now() + "')");
                }
            }
            return null;
        });
    }

    private static int currentVersion(Statement statement) throws Exception {
        try (ResultSet rs = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_history")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }
}
