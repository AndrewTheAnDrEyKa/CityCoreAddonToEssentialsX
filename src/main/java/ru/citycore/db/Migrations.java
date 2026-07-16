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
    ), List.of(
            "ALTER TABLE business ADD COLUMN activity_type TEXT NOT NULL DEFAULT 'GENERAL'",
            "CREATE TABLE IF NOT EXISTS membership_event(id TEXT PRIMARY KEY,player_uuid TEXT NOT NULL,city_id TEXT NOT NULL,event_type TEXT NOT NULL,old_role TEXT,new_role TEXT,actor_uuid TEXT,reason TEXT NOT NULL,created_at TEXT NOT NULL,FOREIGN KEY(player_uuid) REFERENCES player_profile(uuid),FOREIGN KEY(city_id) REFERENCES city(id))",
            "CREATE INDEX IF NOT EXISTS idx_membership_event_player ON membership_event(player_uuid,created_at)",
            "CREATE INDEX IF NOT EXISTS idx_membership_event_city ON membership_event(city_id,created_at)",
            "CREATE TABLE IF NOT EXISTS mayor_transfer_request(id TEXT PRIMARY KEY,city_id TEXT NOT NULL,from_uuid TEXT NOT NULL,to_uuid TEXT NOT NULL,status TEXT NOT NULL,created_at TEXT NOT NULL,decided_at TEXT,decided_by TEXT,FOREIGN KEY(city_id) REFERENCES city(id),FOREIGN KEY(from_uuid) REFERENCES player_profile(uuid),FOREIGN KEY(to_uuid) REFERENCES player_profile(uuid))",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_mayor_transfer_pending_city ON mayor_transfer_request(city_id) WHERE status='PENDING'",
            "CREATE INDEX IF NOT EXISTS idx_mayor_transfer_target ON mayor_transfer_request(to_uuid,status,created_at)",
            "CREATE TABLE IF NOT EXISTS mayor_resignation_request(id TEXT PRIMARY KEY,city_id TEXT NOT NULL,mayor_uuid TEXT NOT NULL,status TEXT NOT NULL,reason TEXT NOT NULL,created_at TEXT NOT NULL,decided_at TEXT,decided_by TEXT,FOREIGN KEY(city_id) REFERENCES city(id),FOREIGN KEY(mayor_uuid) REFERENCES player_profile(uuid))",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_mayor_resignation_pending_city ON mayor_resignation_request(city_id) WHERE status='PENDING'",
            "CREATE INDEX IF NOT EXISTS idx_mayor_resignation_status ON mayor_resignation_request(status,created_at)",
            "CREATE TABLE IF NOT EXISTS resource_deposit(id TEXT PRIMARY KEY,slug TEXT NOT NULL UNIQUE,name TEXT NOT NULL,city_id TEXT NOT NULL,world_uuid TEXT NOT NULL,world_name TEXT NOT NULL,x INTEGER NOT NULL,y INTEGER NOT NULL,z INTEGER NOT NULL,radius INTEGER NOT NULL,reserve_total INTEGER NOT NULL CHECK(reserve_total>0),reserve_remaining INTEGER NOT NULL CHECK(reserve_remaining>=0),throughput_limit INTEGER NOT NULL CHECK(throughput_limit>0),status TEXT NOT NULL,created_by TEXT NOT NULL,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,FOREIGN KEY(city_id) REFERENCES city(id))",
            "CREATE INDEX IF NOT EXISTS idx_resource_deposit_city_status ON resource_deposit(city_id,status)",
            "CREATE TABLE IF NOT EXISTS resource_plot(id TEXT PRIMARY KEY,deposit_id TEXT NOT NULL,business_id TEXT NOT NULL,status TEXT NOT NULL,lease_minor INTEGER NOT NULL CHECK(lease_minor>=0),assigned_by TEXT NOT NULL,assigned_at TEXT NOT NULL,released_at TEXT,FOREIGN KEY(deposit_id) REFERENCES resource_deposit(id),FOREIGN KEY(business_id) REFERENCES business(id))",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_resource_plot_active_deposit ON resource_plot(deposit_id) WHERE status='ACTIVE'",
            "CREATE INDEX IF NOT EXISTS idx_resource_plot_business ON resource_plot(business_id,status)",
            "CREATE TABLE IF NOT EXISTS industrial_controller(serial TEXT PRIMARY KEY,business_id TEXT NOT NULL,issued_to_uuid TEXT NOT NULL,state TEXT NOT NULL,issued_at TEXT NOT NULL,world_uuid TEXT,world_name TEXT,x INTEGER,y INTEGER,z INTEGER,placed_at TEXT,object_id TEXT UNIQUE,FOREIGN KEY(business_id) REFERENCES business(id),FOREIGN KEY(issued_to_uuid) REFERENCES player_profile(uuid))",
            "CREATE INDEX IF NOT EXISTS idx_controller_business_state ON industrial_controller(business_id,state)",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_controller_location ON industrial_controller(world_uuid,x,y,z) WHERE state IN ('PLACED','BOUND')",
            "CREATE TABLE IF NOT EXISTS industrial_object(id TEXT PRIMARY KEY,business_id TEXT NOT NULL,deposit_id TEXT NOT NULL,controller_serial TEXT NOT NULL UNIQUE,level INTEGER,status TEXT NOT NULL,inspection_note TEXT NOT NULL DEFAULT '',submitted_at TEXT NOT NULL,inspected_at TEXT,inspected_by TEXT,next_cycle_at TEXT,last_cycle_at TEXT,debt_minor INTEGER NOT NULL DEFAULT 0 CHECK(debt_minor>=0),last_error TEXT NOT NULL DEFAULT '',updated_at TEXT NOT NULL,FOREIGN KEY(business_id) REFERENCES business(id),FOREIGN KEY(deposit_id) REFERENCES resource_deposit(id),FOREIGN KEY(controller_serial) REFERENCES industrial_controller(serial))",
            "ALTER TABLE industrial_object ADD COLUMN inspection_world_uuid TEXT",
            "ALTER TABLE industrial_object ADD COLUMN inspection_world_name TEXT",
            "ALTER TABLE industrial_object ADD COLUMN inspection_x INTEGER",
            "ALTER TABLE industrial_object ADD COLUMN inspection_y INTEGER",
            "ALTER TABLE industrial_object ADD COLUMN inspection_z INTEGER",
            "CREATE INDEX IF NOT EXISTS idx_industrial_object_business ON industrial_object(business_id,status)",
            "CREATE INDEX IF NOT EXISTS idx_industrial_object_due ON industrial_object(next_cycle_at,status)",
            "CREATE TABLE IF NOT EXISTS industrial_cycle(id TEXT PRIMARY KEY,object_id TEXT NOT NULL,period_key TEXT NOT NULL UNIQUE,due_at TEXT NOT NULL,state TEXT NOT NULL,extracted_units INTEGER NOT NULL DEFAULT 0,gross_minor INTEGER NOT NULL DEFAULT 0,maintenance_minor INTEGER NOT NULL DEFAULT 0,lease_minor INTEGER NOT NULL DEFAULT 0,tax_minor INTEGER NOT NULL DEFAULT 0,net_minor INTEGER NOT NULL DEFAULT 0,debt_added_minor INTEGER NOT NULL DEFAULT 0,details TEXT NOT NULL,created_at TEXT NOT NULL,FOREIGN KEY(object_id) REFERENCES industrial_object(id))",
            "CREATE INDEX IF NOT EXISTS idx_industrial_cycle_object ON industrial_cycle(object_id,due_at)",
            "CREATE TABLE IF NOT EXISTS city_industry_policy(city_id TEXT PRIMARY KEY,procurement_account_id TEXT NOT NULL,tax_bps INTEGER NOT NULL CHECK(tax_bps>=0),updated_at TEXT NOT NULL,updated_by TEXT,FOREIGN KEY(city_id) REFERENCES city(id),FOREIGN KEY(procurement_account_id) REFERENCES account(id))"
    ), List.of(
            "CREATE TABLE IF NOT EXISTS device(serial TEXT PRIMARY KEY,owner_uuid TEXT NOT NULL,device_type TEXT NOT NULL,model TEXT NOT NULL,phone_number TEXT,state TEXT NOT NULL,last_channel TEXT,issued_at TEXT NOT NULL,updated_at TEXT NOT NULL,FOREIGN KEY(owner_uuid) REFERENCES player_profile(uuid))",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_device_active_owner_type ON device(owner_uuid,device_type) WHERE state='ACTIVE'",
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_device_phone_number ON device(phone_number) WHERE phone_number IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_device_owner_state ON device(owner_uuid,state,device_type)",
            "CREATE TABLE IF NOT EXISTS communication_event(id TEXT PRIMARY KEY,event_type TEXT NOT NULL,actor_uuid TEXT NOT NULL,peer_uuid TEXT,device_serial TEXT,channel TEXT,details TEXT NOT NULL,created_at TEXT NOT NULL,FOREIGN KEY(actor_uuid) REFERENCES player_profile(uuid))",
            "CREATE INDEX IF NOT EXISTS idx_communication_actor_created ON communication_event(actor_uuid,created_at)",
            "CREATE INDEX IF NOT EXISTS idx_communication_peer_created ON communication_event(peer_uuid,created_at)"
    ));

    private Migrations() {}

    public static int latestVersion() { return VERSIONS.size(); }

    public static void apply(Database database) {
        apply(database, VERSIONS.size());
    }

    static void apply(Database database, int targetVersion) {
        if (targetVersion < 0 || targetVersion > VERSIONS.size()) {
            throw new IllegalArgumentException("Некорректная версия схемы: " + targetVersion);
        }
        database.transaction(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(HISTORY);
                int current = currentVersion(statement);
                if (current > targetVersion) throw new IllegalStateException("Схема новее запрошенной версии");
                for (int version = current + 1; version <= targetVersion; version++) {
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
