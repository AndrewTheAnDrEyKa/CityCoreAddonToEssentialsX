package ru.citycore.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinorUnitsTest {
    @Test void parsesExactDecimalWithoutFloatingPoint() { assertEquals(12345, MinorUnits.parse("123.45", 2)); }
    @Test void rejectsTooManyFractionDigits() { assertThrows(ArithmeticException.class, () -> MinorUnits.parse("1.001", 2)); }
    @Test void rejectsScientificAndNegativeInput() {
        assertThrows(NumberFormatException.class, () -> MinorUnits.parse("1e3", 2));
        assertThrows(NumberFormatException.class, () -> MinorUnits.parse("-1", 2));
    }
    @Test void formatsMinorUnits() { assertEquals("123.45", MinorUnits.format(12345, 2)); }
}
