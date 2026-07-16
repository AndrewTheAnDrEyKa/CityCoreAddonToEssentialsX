package ru.citycore.config;

import org.bukkit.Sound;

import java.util.List;

/** Hot-reloadable voice, phone and radio settings for Alpha 20. */
public record CommunicationSettings(
        boolean localChatEnabled,
        double speechRadius,
        double shoutRadius,
        String shoutPrefix,
        boolean phoneEnabled,
        int callTimeoutSeconds,
        int callCooldownSeconds,
        boolean radioEnabled,
        List<String> radioChannels,
        String defaultRadioChannel,
        boolean issueStarterPhone,
        boolean issueStarterRadio,
        float soundVolume,
        Sound ringtoneSound,
        Sound callConnectedSound,
        Sound callEndedSound,
        Sound radioTransmitSound
) {
    public CommunicationSettings {
        radioChannels = List.copyOf(radioChannels);
    }
}
