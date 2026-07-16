package ru.citycore.communication;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CallRegistryTest {
    @Test void callMovesFromRingingToConnectedAndEndsOnce() {
        CallRegistry registry = new CallRegistry();
        UUID caller = UUID.randomUUID(); UUID target = UUID.randomUUID(); Instant now = Instant.parse("2026-07-16T10:00:00Z");
        CallSession ringing = registry.start(caller, target, now, Duration.ofSeconds(30));
        assertEquals(CallState.RINGING, ringing.state());
        CallSession connected = registry.accept(target, now.plusSeconds(3));
        assertEquals(CallState.CONNECTED, connected.state());
        assertEquals(target, connected.partner(caller));
        assertEquals(connected.id(), registry.hangup(caller).id());
        assertTrue(registry.call(caller).isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.hangup(target));
    }

    @Test void busyParticipantAndLateAcceptAreRejected() {
        CallRegistry registry = new CallRegistry();
        UUID first = UUID.randomUUID(); UUID second = UUID.randomUUID(); UUID third = UUID.randomUUID();
        Instant now = Instant.parse("2026-07-16T10:00:00Z");
        registry.start(first, second, now, Duration.ofSeconds(10));
        assertThrows(IllegalStateException.class, () -> registry.start(third, second, now, Duration.ofSeconds(10)));
        assertThrows(IllegalStateException.class, () -> registry.accept(second, now.plusSeconds(11)));
        assertTrue(registry.call(first).isEmpty());
    }

    @Test void onlyTargetMayAcceptOrDecline() {
        CallRegistry registry = new CallRegistry();
        UUID caller = UUID.randomUUID(); UUID target = UUID.randomUUID(); Instant now = Instant.now();
        registry.start(caller, target, now, Duration.ofSeconds(30));
        assertThrows(IllegalStateException.class, () -> registry.accept(caller, now));
        assertThrows(IllegalStateException.class, () -> registry.decline(caller));
    }
}
