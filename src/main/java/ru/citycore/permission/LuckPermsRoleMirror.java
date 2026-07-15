package ru.citycore.permission;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.types.InheritanceNode;
import ru.citycore.CityCorePlugin;
import ru.citycore.city.CityRole;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class LuckPermsRoleMirror implements RoleMirror {
    private final CityCorePlugin plugin;
    private final LuckPerms luckPerms;
    private final Map<String, String> groups;

    private LuckPermsRoleMirror(CityCorePlugin plugin, LuckPerms luckPerms, Map<String, String> groups) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
        this.groups = groups;
    }

    public static RoleMirror create(CityCorePlugin plugin) {
        if (!plugin.config().luckPermsEnabled()
                || plugin.getServer().getPluginManager().getPlugin("LuckPerms") == null) {
            return new DisabledRoleMirror();
        }
        try {
            RoleMirror mirror = new LuckPermsRoleMirror(plugin, LuckPermsProvider.get(),
                    plugin.config().luckPermsGroups());
            plugin.getLogger().info("LuckPerms подключён как декоративное отражение ролей CityCore");
            return mirror;
        } catch (IllegalStateException unavailable) {
            plugin.getLogger().warning("LuckPerms обнаружен, но его API недоступен: " + unavailable.getMessage());
            return new DisabledRoleMirror();
        }
    }

    @Override public void sync(UUID playerId, CityRole role) {
        String target = switch (role == null ? CityRole.CITIZEN : role) {
            case CITIZEN -> role == null ? groups.get("default") : groups.get("citizen");
            case OFFICIAL -> groups.get("official");
            case MAYOR -> groups.get("mayor");
        };
        Set<String> managed = new HashSet<>(groups.values());
        luckPerms.getUserManager().modifyUser(playerId, user -> {
            for (String group : managed) user.data().remove(InheritanceNode.builder(group).build());
            user.data().add(InheritanceNode.builder(target).build());
        }).exceptionally(error -> {
            plugin.getLogger().warning("Не удалось синхронизировать роль LuckPerms для " + playerId + ": " + error.getMessage());
            return null;
        });
    }

    @Override public boolean available() { return true; }

    private static final class DisabledRoleMirror implements RoleMirror {
        @Override public void sync(UUID playerId, CityRole role) {}
        @Override public boolean available() { return false; }
    }
}
