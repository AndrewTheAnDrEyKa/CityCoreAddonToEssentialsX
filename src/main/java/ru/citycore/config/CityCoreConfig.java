package ru.citycore.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Sound;
import org.bukkit.Material;
import ru.citycore.economy.MinorUnits;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;

public record CityCoreConfig(String databaseFile, int poolSize, int currencyScale,
                             boolean emissionEnabled, long emissionMaxMinor,
                             long citizenshipReapplyCooldownSeconds, IndustrySettings industry,
                             CommunicationSettings communication,
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
        IndustrySettings industry = industry(config, scale);
        CommunicationSettings communication = communication(config);
        return new CityCoreConfig(
                config.getString("database.file", "citycore.db"),
                Math.max(1, config.getInt("database.pool-size", 4)),
                scale,
                config.getBoolean("economy.emission-enabled", true),
                emissionMax,
                Math.max(0L, config.getLong("citizenship.reapply-cooldown-seconds", 86400L)),
                industry,
                communication,
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
                config.getBoolean("gui.custom-heads", true),
                config.getBoolean("integrations.luckperms.enabled", true),
                groups
        );
    }

    private static CommunicationSettings communication(FileConfiguration config) {
        double speechRadius = config.getDouble("communication.local-chat.speech-radius", 10.0);
        double shoutRadius = config.getDouble("communication.local-chat.shout-radius", 20.0);
        if (!Double.isFinite(speechRadius) || speechRadius <= 0 || speechRadius > 256) {
            throw new IllegalArgumentException("communication.local-chat.speech-radius должен быть от 0 до 256");
        }
        if (!Double.isFinite(shoutRadius) || shoutRadius < speechRadius || shoutRadius > 512) {
            throw new IllegalArgumentException("communication.local-chat.shout-radius должен быть не меньше радиуса речи и не больше 512");
        }
        String prefix = config.getString("communication.local-chat.shout-prefix", "!");
        if (prefix == null || prefix.isBlank() || prefix.length() > 3) {
            throw new IllegalArgumentException("communication.local-chat.shout-prefix должен содержать от 1 до 3 символов");
        }

        List<String> channels = new ArrayList<>();
        for (String raw : config.getStringList("communication.radio.channels")) {
            if (raw == null) continue;
            String normalized = raw.trim();
            if (!normalized.isBlank() && !channels.contains(normalized)) channels.add(normalized);
        }
        if (channels.isEmpty()) {
            channels.addAll(List.of("100.0", "100.2", "100.4", "100.6", "100.8", "101.0",
                    "101.2", "101.4", "101.6", "101.8", "102.0", "102.2"));
        }
        if (channels.size() > 64) throw new IllegalArgumentException("communication.radio.channels содержит больше 64 каналов");
        String defaultChannel = config.getString("communication.radio.default-channel", channels.getFirst());
        if (defaultChannel == null || !channels.contains(defaultChannel.trim())) {
            throw new IllegalArgumentException("communication.radio.default-channel отсутствует в списке каналов");
        }
        double soundVolume = config.getDouble("communication.sounds.volume", 0.18);
        if (soundVolume < 0 || soundVolume > 1) {
            throw new IllegalArgumentException("communication.sounds.volume должен быть от 0 до 1");
        }
        return new CommunicationSettings(
                config.getBoolean("communication.local-chat.enabled", true),
                speechRadius,
                shoutRadius,
                prefix,
                config.getBoolean("communication.phone.enabled", true),
                Math.max(10, Math.min(120, config.getInt("communication.phone.call-timeout-seconds", 30))),
                Math.max(1, Math.min(60, config.getInt("communication.phone.call-cooldown-seconds", 4))),
                config.getBoolean("communication.radio.enabled", true),
                channels,
                defaultChannel.trim(),
                config.getBoolean("communication.devices.issue-starter-phone", true),
                config.getBoolean("communication.devices.issue-starter-radio", true),
                (float) soundVolume,
                sound(config, "communication.sounds.ringtone", "BLOCK_NOTE_BLOCK_BELL"),
                sound(config, "communication.sounds.call-connected", "BLOCK_AMETHYST_BLOCK_CHIME"),
                sound(config, "communication.sounds.call-ended", "BLOCK_NOTE_BLOCK_HAT"),
                sound(config, "communication.sounds.radio-transmit", "BLOCK_NOTE_BLOCK_BIT")
        );
    }

    private static IndustrySettings industry(FileConfiguration config, int scale) {
        long cycleSeconds = Math.max(30L, config.getLong("industry.cycle-seconds", 3600L));
        int maxCatchUp = Math.max(1, Math.min(24, config.getInt("industry.max-catch-up-periods", 3)));
        Material material;
        String materialName = config.getString("industry.controller-material", "LODESTONE");
        try {
            material = Material.valueOf(materialName == null ? "LODESTONE" : materialName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("industry.controller-material содержит неизвестный материал: " + materialName);
        }
        if (!material.isBlock()) throw new IllegalArgumentException("industry.controller-material должен быть блоком: " + materialName);
        Map<Integer, IndustryLevelSettings> levels = Map.of(
                1, level(config, scale, 1, 10, "25.00", "40.00", 7),
                2, level(config, scale, 2, 25, "25.00", "120.00", 30),
                3, level(config, scale, 3, 60, "25.00", "360.00", 90)
        );
        long debtThreshold = money(config, "industry.debt-suspend-threshold", "1000.00", scale);
        if (debtThreshold <= 0) throw new IllegalArgumentException("industry.debt-suspend-threshold должен быть положительным");
        return new IndustrySettings(
                config.getBoolean("industry.enabled", true), cycleSeconds, maxCatchUp,
                money(config, "industry.default-lease", "20.00", scale),
                debtThreshold,
                material, levels
        );
    }

    private static IndustryLevelSettings level(FileConfiguration config, int scale, int level,
                                                long defaultUnits, String defaultPrice,
                                                String defaultMaintenance, int defaultLicenseDays) {
        String root = "industry.levels." + level + ".";
        long units = Math.max(1L, config.getLong(root + "units-per-cycle", defaultUnits));
        long price = money(config, root + "price-per-unit", defaultPrice, scale);
        long maintenance = money(config, root + "maintenance", defaultMaintenance, scale);
        int licenseDays = Math.max(1, config.getInt(root + "minimum-license-days", defaultLicenseDays));
        return new IndustryLevelSettings(units, price, maintenance, licenseDays);
    }

    private static long money(FileConfiguration config, String path, String fallback, int scale) {
        long value = MinorUnits.parse(config.getString(path, fallback), scale);
        if (value < 0) throw new IllegalArgumentException(path + " не может быть отрицательным");
        return value;
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
