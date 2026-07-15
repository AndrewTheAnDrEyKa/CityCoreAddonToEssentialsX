package ru.citycore.profile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ru.citycore.CityCorePlugin;
import ru.citycore.city.CityService;
import ru.citycore.db.StorageExecutor;
import ru.citycore.permission.RoleMirror;

public final class ProfileListener implements Listener {
    private final CityCorePlugin plugin; private final StorageExecutor storage; private final ProfileRepository profiles;
    private final CityService cities; private final RoleMirror roleMirror;
    public ProfileListener(CityCorePlugin plugin, StorageExecutor storage, ProfileRepository profiles,
                           CityService cities, RoleMirror roleMirror) {
        this.plugin = plugin; this.storage = storage; this.profiles = profiles;
        this.cities = cities; this.roleMirror = roleMirror;
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        var id = event.getPlayer().getUniqueId(); var name = event.getPlayer().getName();
        storage.submit(() -> {
            profiles.upsert(id, name);
            return cities.view(id);
        }).thenAccept(city -> roleMirror.sync(id, city == null ? null : city.role())).exceptionally(error -> {
            plugin.getLogger().severe("Не удалось сохранить профиль " + name + ": " + error.getMessage()); return null;
        });
    }
}
