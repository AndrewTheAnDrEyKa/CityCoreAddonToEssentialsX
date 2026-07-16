package ru.citycore.communication;

import ru.citycore.db.Database;

import java.sql.Connection;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Persistent source of truth for phone and radio identity. */
public final class DeviceRepository {
    private final Database database;

    public DeviceRepository(Database database) {
        this.database = database;
    }

    public Device ensureActive(UUID ownerId, DeviceType type, String defaultChannel) {
        return database.transaction(connection -> {
            Optional<Device> existing = findActive(connection, ownerId, type);
            if (existing.isPresent()) return existing.get();
            Instant now = Instant.now();
            String serial = (type == DeviceType.PHONE ? "PH-" : "RA-")
                    + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(java.util.Locale.ROOT);
            String phoneNumber = type == DeviceType.PHONE ? uniquePhoneNumber(connection) : null;
            String model = type == DeviceType.PHONE ? "CityCore Keypad One" : "CityCore Field Radio";
            try (var statement = connection.prepareStatement("""
                    INSERT INTO device(serial,owner_uuid,device_type,model,phone_number,state,last_channel,issued_at,updated_at)
                    VALUES(?,?,?,?,?,'ACTIVE',?,?,?)
                    """)) {
                statement.setString(1, serial);
                statement.setString(2, ownerId.toString());
                statement.setString(3, type.name());
                statement.setString(4, model);
                statement.setString(5, phoneNumber);
                statement.setString(6, type == DeviceType.RADIO ? defaultChannel : null);
                statement.setString(7, now.toString());
                statement.setString(8, now.toString());
                statement.executeUpdate();
            }
            return findBySerial(connection, serial).orElseThrow();
        });
    }

    public Optional<Device> findActive(UUID ownerId, DeviceType type) {
        return database.transaction(connection -> findActive(connection, ownerId, type));
    }

    public Optional<Device> findBySerial(String serial) {
        return database.transaction(connection -> findBySerial(connection, serial));
    }

    public Device setChannel(UUID ownerId, String channel) {
        return database.transaction(connection -> {
            Device radio = findActive(connection, ownerId, DeviceType.RADIO)
                    .orElseThrow(() -> new IllegalStateException("Активная рация не зарегистрирована."));
            try (var statement = connection.prepareStatement(
                    "UPDATE device SET last_channel=?,updated_at=? WHERE serial=? AND state='ACTIVE'")) {
                statement.setString(1, channel);
                statement.setString(2, Instant.now().toString());
                statement.setString(3, radio.serial());
                if (statement.executeUpdate() != 1) throw new IllegalStateException("Рация была отозвана во время изменения канала.");
            }
            return findBySerial(connection, radio.serial()).orElseThrow();
        });
    }

    public void log(String eventType, UUID actorId, UUID peerId, String serial, String channel, String details) {
        database.transaction(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO communication_event(id,event_type,actor_uuid,peer_uuid,device_serial,channel,details,created_at)
                    VALUES(?,?,?,?,?,?,?,?)
                    """)) {
                statement.setString(1, UUID.randomUUID().toString());
                statement.setString(2, eventType);
                statement.setString(3, actorId.toString());
                statement.setString(4, peerId == null ? null : peerId.toString());
                statement.setString(5, serial);
                statement.setString(6, channel);
                statement.setString(7, details == null ? "" : details);
                statement.setString(8, Instant.now().toString());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private Optional<Device> findActive(Connection connection, UUID ownerId, DeviceType type) throws Exception {
        try (var statement = connection.prepareStatement(
                "SELECT * FROM device WHERE owner_uuid=? AND device_type=? AND state='ACTIVE' LIMIT 1")) {
            statement.setString(1, ownerId.toString());
            statement.setString(2, type.name());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private Optional<Device> findBySerial(Connection connection, String serial) throws Exception {
        try (var statement = connection.prepareStatement("SELECT * FROM device WHERE serial=?")) {
            statement.setString(1, serial);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(read(result)) : Optional.empty();
            }
        }
    }

    private String uniquePhoneNumber(Connection connection) throws Exception {
        for (int attempt = 0; attempt < 32; attempt++) {
            String candidate = String.format(java.util.Locale.ROOT, "555-%07d",
                    ThreadLocalRandom.current().nextInt(10_000_000));
            try (var statement = connection.prepareStatement("SELECT 1 FROM device WHERE phone_number=?")) {
                statement.setString(1, candidate);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return candidate;
                }
            }
        }
        throw new IllegalStateException("Не удалось выделить уникальный телефонный номер.");
    }

    private Device read(ResultSet result) throws Exception {
        return new Device(
                result.getString("serial"),
                UUID.fromString(result.getString("owner_uuid")),
                DeviceType.valueOf(result.getString("device_type")),
                result.getString("model"),
                result.getString("phone_number"),
                result.getString("state"),
                result.getString("last_channel"),
                Instant.parse(result.getString("issued_at")),
                Instant.parse(result.getString("updated_at"))
        );
    }
}
