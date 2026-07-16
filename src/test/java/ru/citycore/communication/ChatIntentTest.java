package ru.citycore.communication;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChatIntentTest {
    @Test void ordinaryMessageRemainsSpeech() {
        assertEquals(new ChatIntent("Добрый вечер", false), ChatIntent.parse("  Добрый вечер  ", "!"));
    }

    @Test void prefixCreatesShoutAndIsRemoved() {
        assertEquals(new ChatIntent("Что произошло?!", true), ChatIntent.parse("! Что произошло?!", "!"));
    }

    @Test void emptyShoutIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ChatIntent.parse("!   ", "!"));
    }
}
