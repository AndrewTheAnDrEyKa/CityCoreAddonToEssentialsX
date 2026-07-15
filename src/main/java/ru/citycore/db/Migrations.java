package ru.citycore.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

public final class Migrations {
    private static final String HISTORY = "CREATE TABLE IF NOT EXISTS schema_history(version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)";
    private static final List<List<String>> VERSIONS = List.of(List.of(
            "CREATE TABLE IF NOT EXISTS player_profile(uuid TEXT PRIMARY KEY, last_name TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS city(id TEXT PRIMARY KEY, slug TEXT NOT NULL UNIQUE, name TEXT NOT NULL, founder_uuid TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, FOREIGN KEY(founder_uuid) REFERENCES player_profile(uuid))",
            "CREATE TABLE IF NOT EXISTS citizenship(player_uuid TEXT NOT NULL, city_id TEXT NOT NULL, role TEXT NOT NULL, joined_at TEXT NOT NULL, PRIMARY KEY(player_uuid, city_id), FOREIGN KEY(player_uuid) REFERENCES player_profile(uuid), FOREIGN KEY(city_id) REFERENCES city(id))",
            "CREATE TABLE IF NOT EXISTS account(id TEXT PRIMARY KEY, owner_type TEXT NOT NULL, owner_id TEXT NOT NULL, currency TEXT NOT NULL, balance_minor INTEGER NOT NULL DEFAULT 0, version INTEGER NOT NULL DEFAULT 0, UNIQUE(owner_type, owner_id, currency))",
            "CREATE TABLE IF NOT EXISTS ledger_entry(id TEXT PRIMARY KEY, idempotency_key TEXT NOT NULL UNIQUE, debit_account TEXT NOT NULL, credit_account TEXT NOT NULL, amount_minor INTEGER NOT NULL CHECK(amount_minor > 0), reason TEXT NOT NULL, actor_uuid TEXT, created_at TEXT NOT NULL, previous_hash TEXT, entry_hash TEXT NOT NULL, FOREIGN KEY(debit_account) REFERENCES account(id), FOREIGN KEY(credit_account) REFERENCES account(id))",
            "CREATE INDEX IF NOT EXISTS idx_citizenship_city ON citizenship(city_id)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_citizenship_player ON citizenship(player_uuid)",
            "CREATE INDEX IF NOT EXISTS idx_ledger_created ON ledger_entry(created_at)"
    ), List.of(
            "CREATE TABLE IF NOT EXISTS citizenship_application(id TEXT PRIMARY KEY, player_uuid TEXT NOT NULL, city_id TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, decided_at TEXT, decided_by TEXT, UNIQUE(player_uuid, city_id), FOREIGN KEY(player_uuid) REFERENCES player_profile(uuid), FOREIGN KEY(city_id) REFERENCES city(id))",
            "CREATE INDEX IF NOT EXISTS idx_application_city_status ON citizenship_application(city_id,status)",
            "CREATE INDEX IF NOT EXISTS idx_application_player ON citizenship_application(player_uuid)",
            "CREATE TABLE IF NOT EXISTS audit_event(id TEXT PRIMARY KEY, event_type TEXT NOT NULL, actor_uuid TEXT, subject_type TEXT NOT NULL, subject_id TEXT NOT NULL, payload TEXT NOT NULL, created_at TEXT NOT NULL, previous_hash TEXT, event_hash TEXT NOT NULL)",
            "CREATE INDEX IF NOT EXISTS idx_audit_subject ON audit_event(subject_type,subject_id,created_at)"
    ), List.of(
            "CREATE TABLE IF NOT EXISTS business(id TEXT PRIMARY KEY, city_id TEXT NOT NULL, owner_uuid TEXT NOT NULL, slug TEXT NOT NULL, name TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, decided_at TEXT, decided_by TEXT, UNIQUE(city_id,slug), FOREIGN KEY(city_id) REFERENCES city(id), FOREIGN KEY(owner_uuid) REFERENCES player_profile(uuid))",
            "CREATE INDEX IF NOT EXISTS idx_business_city_status ON business(city_id,status)",
            "CREATE INDEX IF NOT EXISTS idx_business_owner ON business(owner_uuid)",
            "CREATE TABLE IF NOT EXISTS license(id TEXT PRIMARY KEY, business_id TEXT NOT NULL, license_type TEXT NOT NULL, status TEXT NOT NULL, issued_at TEXT NOT NULL, expires_at TEXT, issued_by TEXT NOT NULL, revoked_at TEXT, revoked_by TEXT, UNIQUE(business_id,license_type), FOREIGN KEY(business_id) REFERENCES business(id))",
            "CREATE INDEX IF NOT EXISTS idx_license_business_status ON license(business_id,status)",
            "CREATE TABLE IF NOT EXISTS vault_transfer(id TEXT PRIMARY KEY, idempotency_key TEXT NOT NULL UNIQUE, player_uuid TEXT NOT NULL, target_account TEXT NOT NULL, amount_minor INTEGER NOT NULL CHECK(amount_minor > 0), direction TEXT NOT NULL, state TEXT NOT NULL, vault_balance_before_minor INTEGER, error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, FOREIGN KEY(target_account) REFERENCES account(id))",
            "CREATE INDEX IF NOT EXISTS idx_vault_transfer_state ON vault_transfer(state,updated_at)"
    ), List.of(
            "CREATE TABLE IF NOT EXISTS city_foundation_application(id TEXT PRIMARY KEY, founder_uuid TEXT NOT NULL, slug TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL, status TEXT NOT NULL, created_at TEXT NOT NULL, decided_at TEXT, decided_by TEXT, decision_note TEXT, FOREIGN KEY(founder_uuid) REFERENCES player_profile(uuid))",
            "CREATE INDEX IF NOT EXISTS idx_foundation_status_created ON city_foundation_application(status,created_at)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_foundation_pending_founder ON city_foundation_application(founder_uuid) WHERE status='PENDING'",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_foundation_pending_slug ON city_foundation_application(slug) WHERE status='PENDING'",
            "CREATE TABLE IF NOT EXISTS monetary_issue(id TEXT PRIMARY KEY, idempotency_key TEXT NOT NULL UNIQUE, actor_uuid TEXT NOT NULL, target_type TEXT NOT NULL, target_id TEXT NOT NULL, target_account TEXT NOT NULL, amount_minor INTEGER NOT NULL CHECK(amount_minor > 0), currency TEXT NOT NULL, reason TEXT NOT NULL, state TEXT NOT NULL, ledger_entry_id TEXT, error TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, FOREIGN KEY(target_account) REFERENCES account(id), FOREIGN KEY(ledger_entry_id) REFERENCES ledger_entry(id))",
            "CREATE INDEX IF NOT EXISTS idx_monetary_issue_state ON monetary_issue(state,updated_at)",
            "CREATE INDEX IF NOT EXISTS idx_monetary_issue_target ON monetary_issue(target_type,target_id,created_at)"
    ));

    private Migrations() {}

    public static void apply(Database database) {
        database.transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(HISTORY);
                int current = currentVersion(statement);
                for (int version = current + 1; version <= VERSIONS.size(); version++) {
                    for (String sql : VERSIONS.get(version - 1)) statement.execute(sql);
                    try (var applied = connection.prepareStatement("INSERT INTO schema_history(version,applied_at) VALUES(?,?)")) {
                        applied.setInt(1, version); applied.setString(2, Instant.now().toString()); applied.executeUpdate();
                    }
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
