package ru.citycore.gui;

import org.bukkit.Sound;
import org.bukkit.entity.Player;
import ru.citycore.CityCorePlugin;

public final class GuiFeedback {
    private final CityCorePlugin plugin;
    public GuiFeedback(CityCorePlugin plugin) { this.plugin = plugin; }
    public void open(Player player) { play(player, Sound.BLOCK_CHEST_OPEN, 0.9f); }
    public void click(Player player) { play(player, Sound.UI_BUTTON_CLICK, 1.1f); }
    public void success(Player player) { play(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.25f); }
    public void failure(Player player) { play(player, Sound.ENTITY_VILLAGER_NO, 0.85f); }
    private void play(Player player, Sound sound, float pitch) {
        if (!plugin.config().guiSounds()) return;
        player.playSound(player.getLocation(), sound, plugin.config().soundVolume(), pitch);
    }
}

