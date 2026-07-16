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
import java.util.function.Function;

public final class ChatPromptService implements Listener {
    private final CityCorePlugin plugin;
    private final GuiFeedback feedback;
    private final Map<UUID, Prompt> prompts = new ConcurrentHashMap<>();
    public ChatPromptService(CityCorePlugin plugin, GuiFeedback feedback) {
        this.plugin = plugin;
        this.feedback = feedback;
    }

    public void begin(Player player, String question, Consumer<String> answer) {
        UUID token = UUID.randomUUID();
        prompts.put(player.getUniqueId(), new Prompt(token, answer));
        player.closeInventory();
        feedback.prompt(player);
        player.sendMessage(Component.empty());
        player.sendMessage(UiText.plain("╭  CityCore  •  ввод данных", NamedTextColor.GOLD));
        player.sendMessage(UiText.plain("│", NamedTextColor.DARK_GRAY));
        player.sendMessage(UiText.plain("│  " + question, NamedTextColor.YELLOW));
        player.sendMessage(UiText.plain("│", NamedTextColor.DARK_GRAY));
        player.sendMessage(UiText.plain("╰  Ответьте в чат  •  отмена: «отмена»  •  срок: 2 мин", NamedTextColor.DARK_GRAY));
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Prompt current = prompts.get(player.getUniqueId());
            if (current != null && current.token().equals(token) && prompts.remove(player.getUniqueId(), current)) {
                if (player.isOnline()) UiText.info(player, "Ввод отменён: время ожидания истекло.");
            }
        }, 20L * 120L);
    }

    public <T> void beginValidated(Player player, String question, Function<String, T> parser,
                                   Consumer<T> answer) {
        begin(player, question, value -> {
            try {
                answer.accept(parser.apply(value));
            } catch (RuntimeException invalid) {
                feedback.failure(player);
                String message = invalid.getMessage() == null ? "Проверьте введённое значение." : invalid.getMessage();
                UiText.error(player, message);
                beginValidated(player, question, parser, answer);
            }
        });
    }

    @EventHandler public void onChat(AsyncChatEvent event) {
        Prompt prompt = prompts.remove(event.getPlayer().getUniqueId());
        if (prompt == null) return;
        event.setCancelled(true);
        String value = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (value.equalsIgnoreCase("отмена") || value.equalsIgnoreCase("cancel")) {
                UiText.info(event.getPlayer(), "Ввод отменён."); return;
            }
            prompt.answer().accept(value);
        });
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { prompts.remove(event.getPlayer().getUniqueId()); }

    private record Prompt(UUID token, Consumer<String> answer) {}
}
