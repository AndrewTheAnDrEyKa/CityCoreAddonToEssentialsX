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
    private final GuiFeedback feedback;
    private final Map<UUID, Consumer<String>> prompts = new ConcurrentHashMap<>();
    public ChatPromptService(CityCorePlugin plugin, GuiFeedback feedback) {
        this.plugin = plugin;
        this.feedback = feedback;
    }

    public void begin(Player player, String question, Consumer<String> answer) {
        prompts.put(player.getUniqueId(), answer);
        player.closeInventory();
        feedback.prompt(player);
        player.sendMessage(Component.empty());
        player.sendMessage(UiText.plain("╭  CityCore · ввод данных", NamedTextColor.GOLD));
        player.sendMessage(UiText.plain("│  " + question, NamedTextColor.YELLOW));
        player.sendMessage(UiText.plain("╰  Ответьте в чат. Для выхода: отмена", NamedTextColor.DARK_GRAY));
    }

    @EventHandler public void onChat(AsyncChatEvent event) {
        Consumer<String> answer = prompts.remove(event.getPlayer().getUniqueId());
        if (answer == null) return;
        event.setCancelled(true);
        String value = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (value.equalsIgnoreCase("отмена") || value.equalsIgnoreCase("cancel")) {
                UiText.info(event.getPlayer(), "Ввод отменён."); return;
            }
            answer.accept(value);
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { prompts.remove(event.getPlayer().getUniqueId()); }
}
