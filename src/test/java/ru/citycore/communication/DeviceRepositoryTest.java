package ru.citycore.communication;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.citycore.db.Database;
import ru.citycore.db.Migrations;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DeviceRepositoryTest {
    @TempDir Path temp;
    private Database database;

    @AfterEach void close() { if (database != null) database.close(); }

    @Test void starterDevicesAreIdempotentAndChannelPersists() {
        database = new Database(temp.resolve("devices.db"), 1);
        Migrations.apply(database);
        UUID owner = UUID.randomUUID(); Instant now = Instant.now();
        database.transaction(connection -> {
            try (var statement = connection.prepareStatement(
                    "INSERT INTO player_profile(uuid,last_name,created_at,updated_at) VALUES(?,?,?,?)")) {
                statement.setString(1, owner.toString()); statement.setString(2, "RadioTester");
                statement.setString(3, now.toString()); statement.setString(4, now.toString()); statement.executeUpdate();
            }
            return null;
        });
        DeviceRepository repository = new DeviceRepository(database);
        Device phone = repository.ensureActive(owner, DeviceType.PHONE, "100.0");
        assertEquals(phone.serial(), repository.ensureActive(owner, DeviceType.PHONE, "100.0").serial());
        assertNotNull(phone.phoneNumber());
        Device radio = repository.ensureActive(owner, DeviceType.RADIO, "100.0");
        assertEquals("100.8", repository.setChannel(owner, "100.8").lastChannel());
        assertEquals(radio.serial(), repository.findActive(owner, DeviceType.RADIO).orElseThrow().serial());
    }
}
