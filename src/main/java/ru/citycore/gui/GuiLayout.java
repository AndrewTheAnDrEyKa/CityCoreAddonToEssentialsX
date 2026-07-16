package ru.citycore.gui;

import java.util.ArrayList;
import java.util.List;

/** Stable geometry shared by every CityCore inventory screen. */
public final class GuiLayout {
    public static final int SIZE = 54;
    public static final int BACK_SLOT = 48;
    public static final int HOME_SLOT = 49;
    public static final int CLOSE_SLOT = 50;
    private static final int[] HOME_CATEGORY_SLOTS = {21, 22, 23};

    private static final int[] CONTENT_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };

    private GuiLayout() {}

    public static int[] contentSlots() { return CONTENT_SLOTS.clone(); }
    public static int[] homeCategorySlots() { return HOME_CATEGORY_SLOTS.clone(); }

    public static boolean isFrameSlot(int slot) {
        if (slot < 0 || slot >= SIZE) return false;
        int row = slot / 9;
        int column = slot % 9;
        return row == 0 || (row >= 1 && row <= 4 && (column == 0 || column == 8));
    }

    public static int[] frameSlots() {
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < SIZE; slot++) if (isFrameSlot(slot)) slots.add(slot);
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Returns a contiguous row centered inside the seven-column content area. */
    public static int[] centeredRow(int row, int count) {
        if (row < 1 || row > 4) throw new IllegalArgumentException("content row must be 1..4");
        int safe = Math.max(0, Math.min(7, count));
        int[] result = new int[safe];
        int firstColumn = 1 + (7 - safe) / 2;
        for (int i = 0; i < safe; i++) result[i] = row * 9 + firstColumn + i;
        return result;
    }
}
