package ru.citycore.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.citycore.CityCorePlugin;
import ru.citycore.communication.ChatIntent;
import ru.citycore.communication.CommunicationService;
import ru.citycore.gui.UiText;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /radio command tree and /rf, /rc focused shortcuts. */
public final class RadioCommand implements CommandExecutor, TabCompleter {
    private final CityCorePlugin plugin;
    private final CommunicationService communication;

    public RadioCommand(CityCorePlugin plugin, CommunicationService communication) {
        this.plugin = plugin;
        this.communication = communication;
    }

    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                       @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            UiText.info(sender, "Радиокоманды доступны только игроку.");
            return true;
        }
        try {
            switch (command.getName().toLowerCase(Locale.ROOT)) {
                case "radiofrequency" -> {
                    if (args.length == 0) UiText.info(player, "Текущий канал: " + communication.channel(player.getUniqueId()));
                    else if (args.length == 1) communication.setChannel(player, args[0]);
                    else throw new IllegalArgumentException("Использование: /rf <канал>");
                }
                case "radiotransmit" -> transmit(player, args);
                default -> handleRadio(player, args);
            }
        } catch (RuntimeException error) {
            UiText.error(player, message(error));
        }
        return true;
    }

    private void handleRadio(Player player, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("toggle")) {
            communication.toggleRadio(player);
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "off", "выкл" -> communication.disableRadio(player);
            case "status", "статус" -> UiText.info(player,
                    "Рация: " + (communication.radioEnabled(player.getUniqueId()) ? "включена" : "выключена")
                            + " · канал " + communication.channel(player.getUniqueId()));
            case "list", "channels", "каналы" -> UiText.info(player,
                    "Каналы: " + String.join(", ", communication.channels()));
            case "set", "channel", "канал" -> {
                if (args.length != 2) throw new IllegalArgumentException("Использование: /radio set <канал>");
                communication.setChannel(player, args[1]);
            }
            case "send", "say", "передать" -> transmit(player, Arrays.copyOfRange(args, 1, args.length));
            default -> throw new IllegalArgumentException("Использование: /radio [set <канал>|list|status|off|send <текст>]");
        }
    }

    private void transmit(Player player, String[] args) {
        if (args.length == 0) throw new IllegalArgumentException("Введите сообщение для радиоэфира.");
        communication.transmitOnce(player, ChatIntent.parse(String.join(" ", args),
                plugin.config().communication().shoutPrefix()));
    }

    @Override public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("radiofrequency")) {
            return args.length == 1 ? filter(communication.channels(), args[0]) : List.of();
        }
        if (!command.getName().equalsIgnoreCase("radio")) return List.of();
        if (args.length == 1) return filter(List.of("set", "list", "status", "off", "send"), args[0]);
        if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("channel"))) {
            return filter(communication.channels(), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }

    private String message(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? "Радиооперация не выполнена." : current.getMessage();
    }
}
