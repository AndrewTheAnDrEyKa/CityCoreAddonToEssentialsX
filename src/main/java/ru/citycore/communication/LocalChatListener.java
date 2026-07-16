package ru.citycore.communication;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import ru.citycore.CityCorePlugin;
import ru.citycore.gui.UiText;

/** Replaces global chat with configurable proximity speech after prompt handlers had a chance to consume input. */
public final class LocalChatListener implements Listener {
    private final CityCorePlugin plugin;
    private final CommunicationService communication;

    public LocalChatListener(CityCorePlugin plugin, CommunicationService communication) {
        this.plugin = plugin;
        this.communication = communication;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.config().communication().localChatEnabled()) return;
        event.setCancelled(true);
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!event.getPlayer().isOnline()) return;
            try {
                communication.deliver(event.getPlayer(), ChatIntent.parse(raw,
                        plugin.config().communication().shoutPrefix()));
            } catch (IllegalArgumentException invalid) {
                UiText.error(event.getPlayer(), invalid.getMessage());
            }
        });
    }
}
