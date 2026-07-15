package ru.citycore.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.citycore.CityCorePlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class ChatPromptService implements Listener {
    private final CityCorePlugin plugin;
    private final Map<UUID, Consumer<String>> prompts = new ConcurrentHashMap<>();
    public ChatPromptService(CityCorePlugin plugin) { this.plugin = plugin; }

    public void begin(Player player, String question, Consumer<String> answer) {
        prompts.put(player.getUniqueId(), answer);
        player.closeInventory();
        player.sendMessage(Component.text("CityCore · Ввод", NamedTextColor.GOLD));
        player.sendMessage(Component.text(question, NamedTextColor.YELLOW));
        player.sendMessage(Component.text("Напишите ответ в чат или «отмена».", NamedTextColor.GRAY));
    }

    @EventHandler public void onChat(AsyncChatEvent event) {
        Consumer<String> answer = prompts.remove(event.getPlayer().getUniqueId());
        if (answer == null) return;
        event.setCancelled(true);
        String value = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (value.equalsIgnoreCase("отмена") || value.equalsIgnoreCase("cancel")) {
                event.getPlayer().sendMessage(Component.text("Ввод отменён.", NamedTextColor.GRAY)); return;
            }
            answer.accept(value);
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { prompts.remove(event.getPlayer().getUniqueId()); }
}
