package ru.citycore.industry;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.citycore.CityCorePlugin;
import ru.citycore.gui.UiText;

import java.util.List;

public final class ControllerItems {
    private final NamespacedKey serialKey;
    private final NamespacedKey kindKey;

    public ControllerItems(CityCorePlugin plugin) {
        serialKey = new NamespacedKey(plugin, "controller_serial");
        kindKey = new NamespacedKey(plugin, "controller_kind");
    }

    public ItemStack create(Material material, String serial, String businessName) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(UiText.name("Контроллер нефтяного объекта"));
            meta.lore(List.of(
                    UiText.lore("Предприятие: " + businessName),
                    UiText.lore("Серийный номер: " + serial),
                    Component.empty(),
                    UiText.hint("Установите блок и нажмите ПКМ"),
                    UiText.hint("Копия серийного номера не создаёт новый объект")
            ));
            meta.getPersistentDataContainer().set(serialKey, PersistentDataType.STRING, serial);
            meta.getPersistentDataContainer().set(kindKey, PersistentDataType.STRING, "OIL_CONTROLLER");
        });
        return item;
    }

    public String serial(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return null;
        String kind = item.getItemMeta().getPersistentDataContainer().get(kindKey, PersistentDataType.STRING);
        if (!"OIL_CONTROLLER".equals(kind)) return null;
        return item.getItemMeta().getPersistentDataContainer().get(serialKey, PersistentDataType.STRING);
    }
}
