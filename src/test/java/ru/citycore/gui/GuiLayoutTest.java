package ru.citycore.gui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class GuiLayoutTest {
    @Test void homeHasExactlyThreeCenteredCategorySlots() {
        assertArrayEquals(new int[]{21, 22, 23}, GuiLayout.homeCategorySlots());
        for (int slot : GuiLayout.homeCategorySlots()) assertFalse(GuiLayout.isFrameSlot(slot));
    }

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

    @Test void rowsAreCenteredAndContiguous() {
        assertArrayEquals(new int[0], GuiLayout.centeredRow(2, 0));
        assertArrayEquals(new int[]{22}, GuiLayout.centeredRow(2, 1));
        assertArrayEquals(new int[]{21, 22, 23}, GuiLayout.centeredRow(2, 3));
        assertArrayEquals(new int[]{19, 20, 21, 22, 23, 24, 25}, GuiLayout.centeredRow(2, 7));
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.centeredRow(0, 3));
        assertThrows(IllegalArgumentException.class, () -> GuiLayout.centeredRow(5, 3));
    }
}
