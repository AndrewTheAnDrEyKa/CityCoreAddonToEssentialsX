package ru.citycore.gui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class GuiIconTest {
    @Test void everySemanticIconHasFallback() {
        assertTrue(Arrays.stream(GuiIcon.values()).allMatch(icon -> icon.fallback() != null));
    }

    @Test void customTexturesAreMinecraftHashes() {
        Arrays.stream(GuiIcon.values()).filter(GuiIcon::customHead).forEach(icon ->
                assertTrue(icon.textureHash().matches("[0-9a-f]{32,64}"), icon.name()));
    }
}
