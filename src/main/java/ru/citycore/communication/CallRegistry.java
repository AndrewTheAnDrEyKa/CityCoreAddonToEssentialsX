package ru.citycore.communication;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Deterministic single-call state machine; all mutations are expected on the server thread. */
public final class CallRegistry {
    private final Map<UUID, CallSession> byParticipant = new HashMap<>();

    public CallSession start(UUID callerId, UUID targetId, Instant now, Duration timeout) {
        if (callerId.equals(targetId)) throw new IllegalArgumentException("Нельзя позвонить самому себе.");
        if (byParticipant.containsKey(callerId)) throw new IllegalStateException("Сначала завершите текущий звонок.");
        if (byParticipant.containsKey(targetId)) throw new IllegalStateException("Абонент уже разговаривает.");
        CallSession session = new CallSession(UUID.randomUUID(), callerId, targetId, CallState.RINGING,
                now, now.plus(timeout), null);
        byParticipant.put(callerId, session);
        byParticipant.put(targetId, session);
        return session;
    }

    public CallSession accept(UUID targetId, Instant now) {
        CallSession current = byParticipant.get(targetId);
        if (current == null || current.state() != CallState.RINGING || !current.targetId().equals(targetId)) {
            throw new IllegalStateException("У вас нет входящего звонка.");
        }
        if (!now.isBefore(current.expiresAt())) {
            remove(current);
            throw new IllegalStateException("Входящий звонок уже завершён.");
        }
        CallSession connected = new CallSession(current.id(), current.callerId(), current.targetId(),
                CallState.CONNECTED, current.createdAt(), current.expiresAt(), now);
        byParticipant.put(connected.callerId(), connected);
        byParticipant.put(connected.targetId(), connected);
        return connected;
    }

    public CallSession decline(UUID targetId) {
        CallSession current = byParticipant.get(targetId);
        if (current == null || current.state() != CallState.RINGING || !current.targetId().equals(targetId)) {
            throw new IllegalStateException("У вас нет входящего звонка.");
        }
        remove(current);
        return current;
    }

    public CallSession hangup(UUID playerId) {
        CallSession current = byParticipant.get(playerId);
        if (current == null) throw new IllegalStateException("Активного звонка нет.");
        remove(current);
        return current;
    }

    public Optional<CallSession> call(UUID playerId) {
        return Optional.ofNullable(byParticipant.get(playerId));
    }

    public Optional<CallSession> connected(UUID playerId) {
        return call(playerId).filter(call -> call.state() == CallState.CONNECTED);
    }

    public Optional<CallSession> expire(UUID callId, Instant now) {
        CallSession candidate = byParticipant.values().stream()
                .filter(call -> call.id().equals(callId))
                .findFirst().orElse(null);
        if (candidate == null || candidate.state() != CallState.RINGING || now.isBefore(candidate.expiresAt())) {
            return Optional.empty();
        }
        remove(candidate);
        return Optional.of(candidate);
    }

    private void remove(CallSession session) {
        byParticipant.remove(session.callerId(), session);
        byParticipant.remove(session.targetId(), session);
    }
}
