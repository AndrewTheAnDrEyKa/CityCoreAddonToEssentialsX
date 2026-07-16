package ru.citycore.communication;

import java.time.Instant;
import java.util.UUID;

public record Device(
        String serial,
        UUID ownerId,
        DeviceType type,
        String model,
        String phoneNumber,
        String state,
        String lastChannel,
        Instant issuedAt,
        Instant updatedAt
) {
    public boolean active() { return "ACTIVE".equals(state); }
}
