package ru.citycore.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Sound;

import java.util.Locale;

public record CityCoreConfig(String databaseFile, int poolSize, int currencyScale,
                             boolean warnOfflineMode, String guiTitle, int guiRows,
                             boolean guiSounds, float soundVolume, boolean soundOpen,
                             boolean soundClick, boolean soundResults,
                             Sound soundOpenEffect, Sound soundClickEffect, Sound soundSuccessEffect,
                             Sound soundFailureEffect, Sound soundPromptEffect, boolean customHeads) {
    public static CityCoreConfig from(FileConfiguration config) {
        int scale = config.getInt("economy.currency-scale", 2);
        if (scale < 0 || scale > 6) throw new IllegalArgumentException("economy.currency-scale должен быть от 0 до 6");
        double volume = config.getDouble("gui.sound-volume", 0.55);
        if (volume < 0 || volume > 1) throw new IllegalArgumentException("gui.sound-volume должен быть от 0 до 1");
        return new CityCoreConfig(
                config.getString("database.file", "citycore.db"),
                Math.max(1, config.getInt("database.pool-size", 4)),
                scale,
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
                config.getBoolean("gui.custom-heads", false)
        );
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
