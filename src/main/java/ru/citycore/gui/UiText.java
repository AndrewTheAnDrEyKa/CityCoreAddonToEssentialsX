package ru.citycore.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

public final class UiText {
    private UiText() {}
    public static Component name(String text) {
        return plain(text, NamedTextColor.GOLD);
    }
    public static Component lore(String text) {
        return plain(text, NamedTextColor.GRAY);
    }
    public static Component hint(String text) {
        return plain(text, NamedTextColor.DARK_GRAY);
    }
    public static Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }
    public static void info(CommandSender target, String text) { send(target, text, NamedTextColor.GRAY); }
    public static void success(CommandSender target, String text) { send(target, text, NamedTextColor.GREEN); }
    public static void error(CommandSender target, String text) { send(target, text, NamedTextColor.RED); }
    public static void send(CommandSender target, String text, NamedTextColor color) {
        target.sendMessage(Component.text("CityCore", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                .append(plain("  •  ", NamedTextColor.DARK_GRAY))
                .append(plain(text, color)));
    }
}
