package ru.citycore.config;

import org.bukkit.Material;

import java.util.Map;

public record IndustrySettings(boolean enabled, long cycleSeconds, int maxCatchUpPeriods,
                               long defaultLeaseMinor, long debtSuspendMinor,
                               Material controllerMaterial,
                               Map<Integer, IndustryLevelSettings> levels) {
    public IndustryLevelSettings level(int level) {
        IndustryLevelSettings result = levels.get(level);
        if (result == null) throw new IllegalArgumentException("Неизвестный уровень нефтяного объекта: " + level);
        return result;
    }
}
