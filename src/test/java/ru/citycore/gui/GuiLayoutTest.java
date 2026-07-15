package ru.citycore.gui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GuiLayoutTest {
    @Test void frameNeverConsumesContentOrFooter() {
        Set<Integer> frame = Arrays.stream(GuiLayout.frameSlots()).boxed().collect(Collectors.toSet());
        for (int slot : GuiLayout.contentSlots()) assertFalse(frame.contains(slot), "content slot " + slot);
        assertFalse(frame.contains(GuiLayout.BACK_SLOT));
        assertFalse(frame.contains(GuiLayout.HOME_SLOT));
        assertFalse(frame.contains(GuiLayout.CLOSE_SLOT));
        assertEquals(17, frame.size());
    }

    @Test void frameIsTopBarAndFourSidePairs() {
        for (int slot = 0; slot <= 8; slot++) assertTrue(GuiLayout.isFrameSlot(slot));
        for (int row = 1; row <= 4; row++) {
            assertTrue(GuiLayout.isFrameSlot(row * 9));
            assertTrue(GuiLayout.isFrameSlot(row * 9 + 8));
        }
        for (int slot = 45; slot <= 53; slot++) assertFalse(GuiLayout.isFrameSlot(slot));
    }

    @Test void authoritySectionsAreCenteredAndContiguous() {
        assertArrayEquals(new int[0], GuiLayout.authoritySlots(0));
        assertArrayEquals(new int[]{49}, GuiLayout.authoritySlots(1));
        assertArrayEquals(new int[]{48, 49}, GuiLayout.authoritySlots(2));
        assertArrayEquals(new int[]{48, 49, 50}, GuiLayout.authoritySlots(3));
        assertArrayEquals(new int[]{48, 49, 50}, GuiLayout.authoritySlots(8));
    }
}
