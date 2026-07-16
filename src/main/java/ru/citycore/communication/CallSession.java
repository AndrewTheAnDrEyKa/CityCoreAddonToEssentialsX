package ru.citycore.communication;

import java.time.Instant;
import java.util.UUID;

public record CallSession(UUID id, UUID callerId, UUID targetId, CallState state,
                          Instant createdAt, Instant expiresAt, Instant connectedAt) {
    public boolean involves(UUID playerId) { return callerId.equals(playerId) || targetId.equals(playerId); }
    public UUID partner(UUID playerId) {
        if (callerId.equals(playerId)) return targetId;
        if (targetId.equals(playerId)) return callerId;
        throw new IllegalArgumentException("Игрок не участвует в звонке");
    }
}
