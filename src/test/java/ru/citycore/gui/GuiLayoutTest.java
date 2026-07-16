package ru.citycore.gui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GuiLayoutTest {
    @Test void homeHasFiveBalancedApplicationSlots() {
        assertArrayEquals(new int[]{11, 13, 15, 21, 23}, GuiLayout.homeCategorySlots());
        for (int slot : GuiLayout.homeCategorySlots()) assertFalse(GuiLayout.isFrameSlot(slot));
    }

    @Test void frameNeverConsumesContent() {
        Set<Integer> frame = Arrays.stream(GuiLayout.frameSlots()).boxed().collect(Collectors.toSet());
        for (int slot : GuiLayout.contentSlots()) assertFalse(frame.contains(slot), "content slot " + slot);
        assertTrue(frame.contains(GuiLayout.BACK_SLOT));
        assertTrue(frame.contains(GuiLayout.HOME_SLOT));
        assertTrue(frame.contains(GuiLayout.CLOSE_SLOT));
        assertEquals(18, frame.size());
    }

    @Test void frameIsTopAndFooterWithoutSideClutter() {
        for (int slot = 0; slot <= 8; slot++) assertTrue(GuiLayout.isFrameSlot(slot));
        for (int row = 1; row <= 4; row++) {
            assertFalse(GuiLayout.isFrameSlot(row * 9));
            assertFalse(GuiLayout.isFrameSlot(row * 9 + 8));
        }
        for (int slot = 45; slot <= 53; slot++) assertTrue(GuiLayout.isFrameSlot(slot));
    }

    @Test void rowsAreCenteredAndContiguous() {
        assertArrayEquals(new int[0], GuiLayout.centeredRow(2, 0));
        assertArrayEquals(new int[]{22}, GuiLayout.centeredRow(2, 1));
        assertArrayEquals(new int[]{21, 22, 23}, GuiLayout.centeredRow(2, 3));
        assertArrayEquals(new int[]{19, 20, 21, 22, 23, 24, 25}, GuiLayout.centeredRow(2, 7));
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.centeredRow(0, 3));
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.centeredRow(5, 3));
    }
}
