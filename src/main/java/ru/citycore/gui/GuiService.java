package ru.citycore.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.citycore.CityCorePlugin;
import ru.citycore.economy.EconomyGateway;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GuiService {
    public static final int BACK_SLOT = 45, HOME_SLOT = 49, CLOSE_SLOT = 53;
    private final CityCorePlugin plugin;
    private final EconomyGateway economy;
    private final NamespacedKey actionKey;
    private final Map<UUID, Route> context = new ConcurrentHashMap<>();

    public GuiService(CityCorePlugin plugin, EconomyGateway economy) {
        this.plugin = plugin; this.economy = economy;
        this.actionKey = new NamespacedKey(plugin, "gui_action");
    }

    public void open(Player player, Route route) {
        context.put(player.getUniqueId(), route);
        Inventory inventory = plugin.getServer().createInventory(new CityCoreHolder(route), 54,
                Component.text(title(route), NamedTextColor.DARK_GRAY));
        render(player, route, inventory);
        player.openInventory(inventory);
    }

    private void render(Player player, Route route, Inventory inventory) {
        if (route == Route.HOME) {
            inventory.setItem(20, button(Material.NAME_TAG, "Профиль", "route:PROFILE"));
            inventory.setItem(22, button(Material.BELL, "Город", "route:CITY"));
            inventory.setItem(24, button(Material.GOLD_INGOT, "Экономика", "route:ECONOMY"));
            if (player.hasPermission("citycore.admin")) inventory.setItem(31, button(Material.COMMAND_BLOCK, "Администрирование", "route:ADMIN"));
        } else if (route == Route.PROFILE) {
            inventory.setItem(22, info(Material.NAME_TAG, player.getName(), "UUID: " + player.getUniqueId()));
        } else if (route == Route.CITY) {
            inventory.setItem(22, info(Material.BELL, "Городская система", "Создание города появится в следующем инкременте"));
        } else if (route == Route.ECONOMY) {
            long balance = economy.balanceMinor(player.getUniqueId());
            inventory.setItem(22, info(Material.GOLD_INGOT, "Личный баланс", formatMinor(balance)));
        } else if (route == Route.ADMIN) {
            inventory.setItem(22, info(Material.COMPARATOR, "Состояние", "Используйте /cc status"));
        }
        if (route != Route.HOME) inventory.setItem(BACK_SLOT, button(Material.ARROW, "Назад", "back"));
        inventory.setItem(HOME_SLOT, button(Material.COMPASS, "Главная", "route:HOME"));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "Закрыть", "close"));
    }

    public String action(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    public void handle(Player player, Route current, String action) {
        if (action == null) return;
        if (action.equals("close")) player.closeInventory();
        else if (action.equals("back") || action.equals("route:HOME")) open(player, Route.HOME);
        else if (action.startsWith("route:")) {
            Route target;
            try { target = Route.valueOf(action.substring(6)); } catch (IllegalArgumentException ignored) { return; }
            if (target == Route.ADMIN && !player.hasPermission("citycore.admin")) {
                player.sendMessage(Component.text("Недостаточно прав.", NamedTextColor.RED)); return;
            }
            open(player, target);
        }
    }

    private ItemStack button(Material material, String name, String action) {
        ItemStack item = info(material, name);
        item.editMeta(meta -> meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action));
        return item;
    }
    private ItemStack info(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(name, NamedTextColor.GOLD));
            meta.lore(java.util.Arrays.stream(lore).map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        });
        return item;
    }
    private String formatMinor(long amount) { return BigDecimal.valueOf(amount, plugin.config().currencyScale()).toPlainString(); }
    private String title(Route route) { return switch (route) {
        case HOME -> "CityCore · Главная"; case PROFILE -> "CityCore · Профиль";
        case CITY -> "CityCore · Город"; case ECONOMY -> "CityCore · Экономика";
        case ADMIN -> "CityCore · Администрирование";
    }; }
}
