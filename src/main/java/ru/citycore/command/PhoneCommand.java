package ru.citycore.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.citycore.communication.CommunicationService;
import ru.citycore.communication.Device;
import ru.citycore.communication.DeviceType;
import ru.citycore.gui.GuiService;
import ru.citycore.gui.Route;
import ru.citycore.gui.UiText;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Canonical /phone tree plus deliberately small ergonomic shortcuts. */
public final class PhoneCommand implements CommandExecutor, TabCompleter {
    private final GuiService gui;
    private final CommunicationService communication;

    public PhoneCommand(GuiService gui, CommunicationService communication) {
        this.gui = gui;
        this.communication = communication;
    }

    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                                       @NotNull String label, String[] args) {
        if (!(sender instanceof Player player)) {
            UiText.info(sender, "Телефонные команды доступны только игроку.");
            return true;
        }
        try {
            switch (command.getName().toLowerCase(Locale.ROOT)) {
                case "phoneaccept" -> communication.accept(player);
                case "phonedecline" -> communication.decline(player);
                case "phonehangup" -> communication.hangup(player);
                default -> handlePhone(player, args);
            }
        } catch (RuntimeException error) {
            UiText.error(player, message(error));
        }
        return true;
    }

    private void handlePhone(Player player, String[] args) {
        if (args.length == 0) {
            gui.open(player, Route.PHONE_DEVICE);
            return;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("accept") || action.equals("принять")) communication.accept(player);
        else if (action.equals("decline") || action.equals("отклонить")) communication.decline(player);
        else if (action.equals("hangup") || action.equals("end") || action.equals("завершить")) communication.hangup(player);
        else if (action.equals("number") || action.equals("номер")) {
            Device phone = communication.devices().active(player.getUniqueId(), DeviceType.PHONE)
                    .orElseThrow(() -> new IllegalStateException("Телефон не зарегистрирован."));
            UiText.info(player, phone.model() + " · номер " + phone.phoneNumber() + " · " + phone.state());
        } else if (action.equals("call") || action.equals("звонок")) {
            if (args.length != 2) throw new IllegalArgumentException("Использование: /phone call <игрок>");
            communication.startCall(player, args[1]);
        } else if (args.length == 1) {
            communication.startCall(player, args[0]);
        } else throw new IllegalArgumentException("Использование: /phone [call <игрок>|accept|decline|hangup|number]");
    }

    @Override public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                @NotNull String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("phone")) return List.of();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Stream.concat(
                            Stream.of("call", "accept", "decline", "hangup", "number"),
                            Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    )
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .distinct()
                    .toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("call")) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.startsWith(normalized)).toList();
    }

    private String message(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? "Телефонная операция не выполнена." : current.getMessage();
    }
}
