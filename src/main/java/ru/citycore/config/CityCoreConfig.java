package ru.citycore.config;

import org.bukkit.configuration.file.FileConfiguration;

public record CityCoreConfig(String databaseFile, int poolSize, int currencyScale,
                             boolean warnOfflineMode, String guiTitle, int guiRows) {
    public static CityCoreConfig from(FileConfiguration config) {
        return new CityCoreConfig(
                config.getString("database.file", "citycore.db"),
                Math.max(1, config.getInt("database.pool-size", 4)),
                config.getInt("economy.currency-scale", 2),
                config.getBoolean("security.warn-offline-mode", true),
                config.getString("gui.title", "CityCore"),
                Math.max(1, Math.min(6, config.getInt("gui.rows", 6)))
        );
    }
}

