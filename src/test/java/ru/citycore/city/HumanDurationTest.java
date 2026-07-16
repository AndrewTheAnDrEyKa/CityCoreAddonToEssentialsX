package ru.citycore.city;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HumanDurationTest {
    @Test void cooldownUsesReadableUnits() {
        assertEquals("1 мин", CityService.humanDuration(1));
        assertEquals("23 ч 28 мин", CityService.humanDuration(84_480));
        assertEquals("1 д 2 ч", CityService.humanDuration(93_600));
    }
}
