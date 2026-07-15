package ru.citycore.permission;

import org.bukkit.plugin.Plugin;
import ru.citycore.CityCorePlugin;
import ru.citycore.city.CityRole;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Keeps optional permission integrations behind a class-loading boundary.
 *
 * LuckPerms API types must never appear in this class. Otherwise the JVM may
 * resolve those types while CityCore is starting even when LuckPerms is absent.
 */
public final class RoleMirrors {
    private static final String LUCKPERMS_PLUGIN = "LuckPerms";
    private static final String LUCKPERMS_MIRROR = "ru.citycore.permission.LuckPermsRoleMirror";

    private RoleMirrors() {}

    public static RoleMirror create(CityCorePlugin plugin) {
        if (!plugin.config().luckPermsEnabled()) {
            plugin.getLogger().info("Отражение ролей LuckPerms отключено в config.yml");
            return DisabledRoleMirror.INSTANCE;
        }

        Plugin luckPermsPlugin = plugin.getServer().getPluginManager().getPlugin(LUCKPERMS_PLUGIN);
        if (luckPermsPlugin == null || !luckPermsPlugin.isEnabled()) {
            plugin.getLogger().info("LuckPerms не установлен: CityCore использует собственные роли и полномочия");
            return DisabledRoleMirror.INSTANCE;
        }

        try {
            Class<?> integration = Class.forName(LUCKPERMS_MIRROR, true, plugin.getClass().getClassLoader());
            Method factory = integration.getMethod("create", CityCorePlugin.class);
            return (RoleMirror) factory.invoke(null, plugin);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                 | ClassCastException | LinkageError error) {
            plugin.getLogger().warning("LuckPerms обнаружен, но интеграция недоступна: " + error.getMessage());
            return DisabledRoleMirror.INSTANCE;
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            plugin.getLogger().warning("LuckPerms обнаружен, но интеграция не запустилась: " + cause.getMessage());
            return DisabledRoleMirror.INSTANCE;
        }
    }

    private enum DisabledRoleMirror implements RoleMirror {
        INSTANCE;

        @Override public void sync(UUID playerId, CityRole role) {}
        @Override public boolean available() { return false; }
    }
}
