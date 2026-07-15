package ru.citycore.profile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ru.citycore.CityCorePlugin;
import ru.citycore.db.StorageExecutor;

public final class ProfileListener implements Listener {
    private final CityCorePlugin plugin; private final StorageExecutor storage; private final ProfileRepository profiles;
    public ProfileListener(CityCorePlugin plugin, StorageExecutor storage, ProfileRepository profiles) {
        this.plugin = plugin; this.storage = storage; this.profiles = profiles;
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        var id = event.getPlayer().getUniqueId(); var name = event.getPlayer().getName();
        storage.submit(() -> profiles.upsert(id, name)).exceptionally(error -> {
            plugin.getLogger().severe("Не удалось сохранить профиль " + name + ": " + error.getMessage()); return null;
        });
    }
}

