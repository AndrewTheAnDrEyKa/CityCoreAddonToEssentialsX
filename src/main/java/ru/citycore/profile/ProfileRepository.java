package ru.citycore.profile;

import ru.citycore.db.Database;
import ru.citycore.db.DatabaseException;

import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public final class ProfileRepository {
    private final Database database;
    public ProfileRepository(Database database) { this.database = database; }

    public PlayerProfile upsert(UUID uuid, String name) {
        Instant now = Instant.now();
        return database.transaction(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO player_profile(uuid,last_name,created_at,updated_at) VALUES(?,?,?,?)
                    ON CONFLICT(uuid) DO UPDATE SET last_name=excluded.last_name,updated_at=excluded.updated_at
                    """)) {
                statement.setString(1, uuid.toString()); statement.setString(2, name);
                statement.setString(3, now.toString()); statement.setString(4, now.toString());
                statement.executeUpdate();
            }
            return find(connection, uuid).orElseThrow();
        });
    }

    public Optional<PlayerProfile> find(UUID uuid) {
        return database.transaction(connection -> find(connection, uuid));
    }

    private Optional<PlayerProfile> find(java.sql.Connection connection, UUID uuid) throws Exception {
        try (var statement = connection.prepareStatement("SELECT * FROM player_profile WHERE uuid=?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(new PlayerProfile(uuid, rs.getString("last_name"),
                        Instant.parse(rs.getString("created_at")), Instant.parse(rs.getString("updated_at"))));
            }
        }
    }
}

