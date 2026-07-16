package ru.citycore.profile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ru.citycore.CityCorePlugin;
import ru.citycore.city.CityService;
import ru.citycore.db.StorageExecutor;
import ru.citycore.permission.RoleMirror;
import ru.citycore.communication.DeviceService;
import ru.citycore.gui.NavigatorItem;

public final class ProfileListener implements Listener {
    private final CityCorePlugin plugin; private final StorageExecutor storage; private final ProfileRepository profiles;
    private final CityService cities; private final RoleMirror roleMirror; private final DeviceService devices;
    private final NavigatorItem navigator;
    public ProfileListener(CityCorePlugin plugin, StorageExecutor storage, ProfileRepository profiles,
                           CityService cities, RoleMirror roleMirror, DeviceService devices, NavigatorItem navigator) {
        this.plugin = plugin; this.storage = storage; this.profiles = profiles;
        this.cities = cities; this.roleMirror = roleMirror; this.devices = devices; this.navigator = navigator;
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        var id = event.getPlayer().getUniqueId(); var name = event.getPlayer().getName();
        storage.submit(() -> {
            profiles.upsert(id, name);
            return cities.view(id);
        }).thenAccept(city -> {
            roleMirror.sync(id, city == null ? null : city.role());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (event.getPlayer().isOnline()) {
                    navigator.giveMissing(event.getPlayer());
                    devices.loadAndProvision(event.getPlayer());
                }
            });
        }).exceptionally(error -> {
            plugin.getLogger().severe("Не удалось сохранить профиль " + name + ": " + error.getMessage()); return null;
        });
    }
}
