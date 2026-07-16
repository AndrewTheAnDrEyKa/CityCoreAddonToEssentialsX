package ru.citycore.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.citycore.CityCorePlugin;

import java.util.List;
import java.util.Map;

/** Technical, non-RP navigator that opens the project control center. */
public final class NavigatorItem {
    private final NamespacedKey key;

    public NavigatorItem(CityCorePlugin plugin) {
        key = new NamespacedKey(plugin, "project_navigator");
    }

    public ItemStack create() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        item.editMeta(meta -> {
            meta.displayName(text("Панель CityCore", NamedTextColor.AQUA));
            meta.lore(List.of(
                    text("Технический центр проекта", NamedTextColor.GRAY),
                    text("Не является RP-телефоном", NamedTextColor.DARK_GRAY),
                    Component.empty(),
                    text("ПКМ  ›  открыть /cc", NamedTextColor.WHITE)
            ));
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    public boolean matches(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        Byte value = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return value != null && value == (byte) 1;
    }

    public void giveMissing(Player player) {
        for (ItemStack item : player.getInventory().getContents()) if (matches(item)) return;
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(create());
        if (overflow.isEmpty()) UiText.success(player, "Панель CityCore добавлена в инвентарь.");
        else UiText.info(player, "Освободите место: панель CityCore ожидает выдачи.");
    }

    private static Component text(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }
}
