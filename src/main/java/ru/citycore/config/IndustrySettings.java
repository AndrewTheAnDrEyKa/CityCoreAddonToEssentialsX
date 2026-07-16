package ru.citycore.config;

import org.bukkit.Material;

import java.util.Map;

public record IndustrySettings(boolean enabled, long cycleSeconds, int maxCatchUpPeriods,
                               long defaultLeaseMinor, long debtSuspendMinor,
                               int automaticDepositRadius, Map<Integer, Long> automaticReserves,
                               Material controllerMaterial,
                               Map<Integer, IndustryLevelSettings> levels) {
    public IndustryLevelSettings level(int level) {
        IndustryLevelSettings result = levels.get(level);
        if (result == null) throw new IllegalArgumentException("Неизвестный уровень нефтяного объекта: " + level);
        return result;
    }

    public long automaticReserve(int level) {
        Long result = automaticReserves.get(level);
        if (result == null || result <= 0) throw new IllegalArgumentException("Не задан запас участка для уровня " + level);
        return result;
    }
}
