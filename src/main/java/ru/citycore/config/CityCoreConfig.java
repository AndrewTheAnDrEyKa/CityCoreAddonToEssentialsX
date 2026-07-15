package ru.citycore.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Sound;
import ru.citycore.economy.MinorUnits;

import java.util.Map;
import java.util.Locale;

public record CityCoreConfig(String databaseFile, int poolSize, int currencyScale,
                             boolean emissionEnabled, long emissionMaxMinor,
                             boolean warnOfflineMode, String guiTitle, int guiRows,
                             boolean guiSounds, float soundVolume, boolean soundOpen,
                             boolean soundClick, boolean soundResults,
                             Sound soundOpenEffect, Sound soundClickEffect, Sound soundSuccessEffect,
                             Sound soundFailureEffect, Sound soundPromptEffect, boolean customHeads,
                             boolean luckPermsEnabled, Map<String, String> luckPermsGroups) {
    public static CityCoreConfig from(FileConfiguration config) {
        int scale = config.getInt("economy.currency-scale", 2);
        if (scale < 0 || scale > 6) throw new IllegalArgumentException("economy.currency-scale должен быть от 0 до 6");
        double volume = config.getDouble("gui.sound-volume", 0.55);
        if (volume < 0 || volume > 1) throw new IllegalArgumentException("gui.sound-volume должен быть от 0 до 1");
        long emissionMax = MinorUnits.parse(config.getString("economy.emission-max-amount", "1000000.00"), scale);
        if (emissionMax <= 0) throw new IllegalArgumentException("economy.emission-max-amount должен быть положительным");
        Map<String, String> groups = Map.of(
                "default", group(config, "default", "citycore_default"),
                "citizen", group(config, "citizen", "citycore_default"),
                "official", group(config, "official", "citycore_official"),
                "mayor", group(config, "mayor", "citycore_mayor"),
                "police", group(config, "police", "citycore_police")
        );
        return new CityCoreConfig(
                config.getString("database.file", "citycore.db"),
                Math.max(1, config.getInt("database.pool-size", 4)),
                scale,
                config.getBoolean("economy.emission-enabled", true),
                emissionMax,
                config.getBoolean("security.warn-offline-mode", true),
                config.getString("gui.title", "CityCore"),
                Math.max(1, Math.min(6, config.getInt("gui.rows", 6))),
                config.getBoolean("gui.sounds", true),
                (float) volume,
                config.getBoolean("gui.sound-open", true),
                config.getBoolean("gui.sound-click", true),
                config.getBoolean("gui.sound-results", true),
                sound(config, "gui.sound-open-effect", "BLOCK_AMETHYST_BLOCK_CHIME"),
                sound(config, "gui.sound-click-effect", "UI_BUTTON_CLICK"),
                sound(config, "gui.sound-success-effect", "ENTITY_EXPERIENCE_ORB_PICKUP"),
                sound(config, "gui.sound-failure-effect", "BLOCK_NOTE_BLOCK_BASS"),
                sound(config, "gui.sound-prompt-effect", "BLOCK_NOTE_BLOCK_HAT"),
                config.getBoolean("gui.custom-heads", false),
                config.getBoolean("integrations.luckperms.enabled", true),
                groups
        );
    }

    private static String group(FileConfiguration config, String role, String fallback) {
        String value = config.getString("integrations.luckperms.groups." + role, fallback);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Не задана группа LuckPerms для роли " + role);
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static Sound sound(FileConfiguration config, String path, String fallback) {
        String value = config.getString(path, fallback);
        try {
            return Sound.valueOf(value == null ? fallback : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(path + " содержит неизвестный звук: " + value);
        }
    }
}
