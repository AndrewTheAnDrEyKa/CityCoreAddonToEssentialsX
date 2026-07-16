package ru.citycore.communication;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.citycore.CityCorePlugin;

import java.util.List;
import java.util.Locale;

/** Creates and recognizes physical CityCore communication devices. */
public final class DeviceItems {
    private final NamespacedKey typeKey;
    private final NamespacedKey serialKey;

    public DeviceItems(CityCorePlugin plugin) {
        typeKey = new NamespacedKey(plugin, "device_type");
        serialKey = new NamespacedKey(plugin, "device_serial");
    }

    public ItemStack create(Device device) {
        Material material = device.type() == DeviceType.PHONE ? Material.CLOCK : Material.SPYGLASS;
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            NamedTextColor color = device.type() == DeviceType.PHONE ? NamedTextColor.AQUA : NamedTextColor.GOLD;
            meta.displayName(text(device.type() == DeviceType.PHONE ? "Кнопочный телефон" : "Полевая рация", color));
            if (device.type() == DeviceType.PHONE) {
                meta.lore(List.of(
                        text(device.model(), NamedTextColor.GRAY),
                        text("Номер: " + device.phoneNumber(), NamedTextColor.WHITE),
                        Component.empty(),
                        text("ПКМ  ›  открыть телефон", NamedTextColor.DARK_GRAY),
                        text("Команда: /phone или /ph", NamedTextColor.DARK_GRAY)
                ));
            } else {
                meta.lore(List.of(
                        text(device.model(), NamedTextColor.GRAY),
                        text("Канал: " + device.lastChannel(), NamedTextColor.WHITE),
                        Component.empty(),
                        text("ПКМ  ›  включить или выключить", NamedTextColor.DARK_GRAY),
                        text("Shift + ПКМ  ›  меню связи", NamedTextColor.DARK_GRAY)
                ));
            }
            meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, device.type().name());
            meta.getPersistentDataContainer().set(serialKey, PersistentDataType.STRING, device.serial());
        });
        return item;
    }

    public DeviceType type(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        String value = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
        if (value == null) return null;
        try { return DeviceType.valueOf(value.toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    public String serial(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(serialKey, PersistentDataType.STRING);
    }

    public boolean matches(ItemStack item, Device device) {
        return device != null && device.active() && device.type() == type(item) && device.serial().equals(serial(item));
    }

    public boolean isDevice(ItemStack item) { return type(item) != null && serial(item) != null; }

    private static Component text(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }
}
