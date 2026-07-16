package ru.citycore.gui;

import org.bukkit.entity.Player;
import ru.citycore.CityCorePlugin;

public final class GuiFeedback {
    private final CityCorePlugin plugin;
    public GuiFeedback(CityCorePlugin plugin) { this.plugin = plugin; }
    public void open(Player player) { if (plugin.config().soundOpen()) play(player, plugin.config().soundOpenEffect(), 1.65f, 0.75f); }
    public void click(Player player) { if (plugin.config().soundClick()) play(player, plugin.config().soundClickEffect(), 1.35f, 0.42f); }
    public void success(Player player) { if (plugin.config().soundResults()) play(player, plugin.config().soundSuccessEffect(), 1.45f, 0.85f); }
    public void failure(Player player) { if (plugin.config().soundResults()) play(player, plugin.config().soundFailureEffect(), 0.72f, 0.75f); }
    public void prompt(Player player) { if (plugin.config().soundClick()) play(player, plugin.config().soundPromptEffect(), 1.75f, 0.45f); }
    private void play(Player player, org.bukkit.Sound sound, float pitch, float volumeMultiplier) {
        if (!plugin.config().guiSounds()) return;
        player.playSound(player.getLocation(), sound, plugin.config().soundVolume() * volumeMultiplier, pitch);
    }
}
