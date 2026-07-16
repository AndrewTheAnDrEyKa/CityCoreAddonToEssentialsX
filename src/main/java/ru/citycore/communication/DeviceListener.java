package ru.citycore.communication;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import ru.citycore.CityCorePlugin;
import ru.citycore.gui.GuiService;
import ru.citycore.gui.NavigatorItem;
import ru.citycore.gui.Route;
import ru.citycore.gui.UiText;

/** Physical device interactions and safe re-delivery after death. */
public final class DeviceListener implements Listener {
    private final CityCorePlugin plugin;
    private final DeviceService devices;
    private final CommunicationService communication;
    private final GuiService gui;
    private final NavigatorItem navigator;

    public DeviceListener(CityCorePlugin plugin, DeviceService devices,
                          CommunicationService communication, GuiService gui, NavigatorItem navigator) {
        this.plugin = plugin;
        this.devices = devices;
        this.communication = communication;
        this.gui = gui;
        this.navigator = navigator;
    }

    @EventHandler public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (navigator.matches(event.getItem())) {
            event.setCancelled(true);
            gui.open(event.getPlayer(), Route.HOME);
            return;
        }
        DeviceType type = devices.items().type(event.getItem());
        if (type == null) return;
        event.setCancelled(true);
        if (!devices.isUsable(event.getPlayer(), event.getItem())) {
            UiText.error(event.getPlayer(), "Устройство не зарегистрировано, отозвано или принадлежит другому игроку.");
            return;
        }
        try {
            if (type == DeviceType.PHONE) gui.open(event.getPlayer(), Route.PHONE_DEVICE);
            else if (event.getPlayer().isSneaking()) gui.open(event.getPlayer(), Route.RADIO_CHANNELS);
            else communication.toggleRadio(event.getPlayer());
        } catch (RuntimeException error) {
            UiText.error(event.getPlayer(), error.getMessage());
        }
    }

    @EventHandler public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(item -> devices.items().isDevice(item) || navigator.matches(item));
    }

    @EventHandler(ignoreCancelled = true) public void onDrop(PlayerDropItemEvent event) {
        if (!devices.items().isDevice(event.getItemDrop().getItemStack())
                && !navigator.matches(event.getItemDrop().getItemStack())) return;
        event.setCancelled(true);
        UiText.info(event.getPlayer(), "Системное устройство нельзя выбросить. Используйте ПКМ по самому устройству.");
    }

    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!event.getPlayer().isOnline()) return;
            devices.giveMissing(event.getPlayer());
            navigator.giveMissing(event.getPlayer());
        }, 2L);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        communication.quit(event.getPlayer());
        devices.unload(event.getPlayer().getUniqueId());
    }
}
