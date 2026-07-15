package ru.citycore.config;

import org.bukkit.configuration.file.FileConfiguration;

public record CityCoreConfig(String databaseFile, int poolSize, int currencyScale,
                             boolean warnOfflineMode, String guiTitle, int guiRows,
                             boolean guiSounds, float soundVolume, boolean soundOpen,
                             boolean soundClick, boolean soundResults, boolean customHeads) {
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
                config.getBoolean("gui.sound-open", false),
                config.getBoolean("gui.sound-click", false),
                config.getBoolean("gui.sound-results", true),
                config.getBoolean("gui.custom-heads", false)
        );
    }
}
