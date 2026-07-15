package ru.citycore.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.citycore.CityCorePlugin;
import ru.citycore.city.CityService;
import ru.citycore.db.StorageExecutor;
import ru.citycore.gui.GuiService;
import ru.citycore.gui.Route;

import java.util.List;

public final class CityCoreCommand implements CommandExecutor, TabCompleter {
    private final CityCorePlugin plugin; private final GuiService gui; private final StorageExecutor storage; private final CityService cities;
    public CityCoreCommand(CityCorePlugin plugin, GuiService gui, StorageExecutor storage, CityService cities) {
        this.plugin = plugin; this.gui = gui; this.storage = storage; this.cities = cities;
    }
    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) gui.open(player, Route.HOME);
            else sender.sendMessage("Использование: /cc status");
            return true;
        }
        if (args[0].equalsIgnoreCase("status") && sender.hasPermission("citycore.admin.status")) {
            sender.sendMessage(Component.text("CityCore " + plugin.getPluginMeta().getVersion() + " · DB: OK · Vault: " + plugin.economy().available(), NamedTextColor.GREEN));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload") && sender.hasPermission("citycore.admin.reload")) {
            plugin.reloadCityCoreConfig(); sender.sendMessage(Component.text("Конфигурация перечитана.", NamedTextColor.GREEN)); return true;
        }
        if (args[0].equalsIgnoreCase("city") && args.length >= 4 && args[1].equalsIgnoreCase("create") && sender instanceof Player player) {
            String name = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
            sender.sendMessage(Component.text("Создаю город…", NamedTextColor.GRAY));
            storage.submit(() -> cities.create(player.getUniqueId(), args[2], name)).whenComplete((city, error) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (error != null) player.sendMessage(Component.text("Город не создан: " + rootMessage(error), NamedTextColor.RED));
                        else player.sendMessage(Component.text("Город «" + city.name() + "» создан. Вы назначены мэром.", NamedTextColor.GREEN));
                    }));
            return true;
        }
        sender.sendMessage(Component.text("Доступно: /cc, /cc city create <id> <название>, /cc status, /cc reload", NamedTextColor.YELLOW));
        return true;
    }
    @Override public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        return args.length == 1 ? List.of("city", "status", "reload") : args.length == 2 && args[0].equalsIgnoreCase("city") ? List.of("create") : List.of();
    }
    private String rootMessage(Throwable error) {
        Throwable current = error; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
