package ru.citycore.communication;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ru.citycore.CityCorePlugin;
import ru.citycore.db.StorageExecutor;
import ru.citycore.gui.UiText;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Coordinates local speech, calls and configured radio channels on the server thread. */
public final class CommunicationService {
    private final CityCorePlugin plugin;
    private final StorageExecutor storage;
    private final DeviceRepository repository;
    private final DeviceService devices;
    private final CallRegistry calls = new CallRegistry();
    private final Set<UUID> radioMode = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Instant> lastOutgoingCall = new ConcurrentHashMap<>();

    public CommunicationService(CityCorePlugin plugin, StorageExecutor storage,
                                DeviceRepository repository, DeviceService devices) {
        this.plugin = plugin;
        this.storage = storage;
        this.repository = repository;
        this.devices = devices;
    }

    public void startCall(Player caller, String targetName) {
        requirePhone(caller);
        if (!plugin.config().communication().phoneEnabled()) throw new IllegalStateException("Телефонная связь отключена.");
        Player target = Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.getName().equalsIgnoreCase(targetName))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Игрок «" + targetName + "» не найден онлайн."));
        requirePhone(target);
        Instant now = Instant.now();
        Instant last = lastOutgoingCall.get(caller.getUniqueId());
        int cooldown = plugin.config().communication().callCooldownSeconds();
        if (last != null && now.isBefore(last.plusSeconds(cooldown))) {
            long seconds = Math.max(1, Duration.between(now, last.plusSeconds(cooldown)).toSeconds() + 1);
            throw new IllegalStateException("Повторный звонок доступен через " + seconds + " сек.");
        }
        CallSession call = calls.start(caller.getUniqueId(), target.getUniqueId(), now,
                Duration.ofSeconds(plugin.config().communication().callTimeoutSeconds()));
        lastOutgoingCall.put(caller.getUniqueId(), now);
        UiText.info(caller, "Вы звоните игроку " + target.getName() + "…");
        sendIncomingCall(target, caller);
        play(target, plugin.config().communication().ringtoneSound(), 1.35f);
        audit("CALL_STARTED", caller.getUniqueId(), target.getUniqueId(), phoneSerial(caller), null, "ringing");
        long delay = plugin.config().communication().callTimeoutSeconds() * 20L + 2L;
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> calls.expire(call.id(), Instant.now()).ifPresent(expired -> {
            online(expired.callerId()).ifPresent(player -> UiText.info(player, "Абонент не ответил."));
            online(expired.targetId()).ifPresent(player -> UiText.info(player, "Входящий звонок завершён."));
            audit("CALL_MISSED", expired.callerId(), expired.targetId(), phoneSerial(expired.callerId()), null, "timeout");
        }), delay);
    }

    public void accept(Player target) {
        requirePhone(target);
        CallSession call = calls.accept(target.getUniqueId(), Instant.now());
        Player caller = online(call.callerId()).orElseThrow(() -> {
            calls.hangup(target.getUniqueId());
            return new IllegalStateException("Звонивший игрок уже вышел.");
        });
        if (!devices.hasUsable(caller, DeviceType.PHONE)) {
            calls.hangup(target.getUniqueId());
            UiText.info(caller, "Вызов завершён: телефон больше недоступен.");
            throw new IllegalStateException("Телефон звонившего больше недоступен.");
        }
        radioMode.remove(caller.getUniqueId());
        radioMode.remove(target.getUniqueId());
        UiText.success(caller, target.getName() + " принял звонок. Говорите в обычный чат.");
        UiText.success(target, "Соединение с " + caller.getName() + " установлено.");
        caller.sendActionBar(status("ТЕЛЕФОН • " + target.getName(), NamedTextColor.AQUA));
        target.sendActionBar(status("ТЕЛЕФОН • " + caller.getName(), NamedTextColor.AQUA));
        play(caller, plugin.config().communication().callConnectedSound(), 1.55f);
        play(target, plugin.config().communication().callConnectedSound(), 1.55f);
        audit("CALL_CONNECTED", caller.getUniqueId(), target.getUniqueId(), phoneSerial(caller), null, "connected");
    }

    public void decline(Player target) {
        CallSession call = calls.decline(target.getUniqueId());
        online(call.callerId()).ifPresent(caller -> UiText.info(caller, target.getName() + " отклонил звонок."));
        UiText.info(target, "Звонок отклонён.");
        audit("CALL_DECLINED", target.getUniqueId(), call.callerId(), phoneSerial(target), null, "declined");
    }

    public void hangup(Player player) {
        CallSession call = calls.hangup(player.getUniqueId());
        UUID partnerId = call.partner(player.getUniqueId());
        online(partnerId).ifPresent(partner -> {
            UiText.info(partner, player.getName() + " завершил звонок.");
            play(partner, plugin.config().communication().callEndedSound(), 0.85f);
        });
        UiText.info(player, "Звонок завершён.");
        play(player, plugin.config().communication().callEndedSound(), 0.85f);
        audit("CALL_ENDED", player.getUniqueId(), partnerId, phoneSerial(player), null, call.state().name());
    }

    public boolean toggleRadio(Player player) {
        requireRadio(player);
        if (!plugin.config().communication().radioEnabled()) throw new IllegalStateException("Радиосвязь отключена.");
        if (calls.connected(player.getUniqueId()).isPresent()) {
            throw new IllegalStateException("Завершите телефонный звонок перед включением передачи.");
        }
        boolean enabled;
        if (radioMode.remove(player.getUniqueId())) enabled = false;
        else { radioMode.add(player.getUniqueId()); enabled = true; }
        String channel = channel(player.getUniqueId());
        if (enabled) {
            UiText.success(player, "Рация включена. Канал: " + channel + ". Обычный чат теперь передаётся в эфир и поблизости.");
            player.sendActionBar(status("РАЦИЯ • " + channel, NamedTextColor.GOLD));
        } else {
            UiText.info(player, "Рация выключена. Сообщения остаются только локальными.");
            player.sendActionBar(status("РАЦИЯ ВЫКЛЮЧЕНА", NamedTextColor.GRAY));
        }
        play(player, plugin.config().communication().radioTransmitSound(), enabled ? 1.45f : 0.75f);
        audit(enabled ? "RADIO_ENABLED" : "RADIO_DISABLED", player.getUniqueId(), null,
                radioSerial(player), channel, enabled ? "on" : "off");
        return enabled;
    }

    public void disableRadio(Player player) {
        if (radioMode.remove(player.getUniqueId())) {
            UiText.info(player, "Рация выключена.");
            audit("RADIO_DISABLED", player.getUniqueId(), null, radioSerial(player), channel(player.getUniqueId()), "off");
        } else UiText.info(player, "Рация уже выключена.");
    }

    public void setChannel(Player player, String requested) {
        requireRadio(player);
        String channel = plugin.config().communication().radioChannels().stream()
                .filter(value -> value.equalsIgnoreCase(requested.trim()))
                .findFirst().orElseThrow(() -> new IllegalArgumentException(
                        "Неизвестный канал. Доступно: " + String.join(", ", plugin.config().communication().radioChannels())));
        devices.setChannel(player, channel, device -> {
            UiText.success(player, "Канал рации установлен: " + device.lastChannel());
            if (radioMode.contains(player.getUniqueId())) player.sendActionBar(status("РАЦИЯ • " + channel, NamedTextColor.GOLD));
            audit("RADIO_CHANNEL_CHANGED", player.getUniqueId(), null, device.serial(), channel, "channel changed");
        });
    }

    public void deliver(Player sender, ChatIntent intent) {
        if (!plugin.config().communication().localChatEnabled()) return;
        double radius = intent.shout() ? plugin.config().communication().shoutRadius()
                : plugin.config().communication().speechRadius();
        Map<UUID, Component> deliveries = new HashMap<>();
        Component local = localMessage(sender, intent);
        double radiusSquared = radius * radius;
        for (Player recipient : Bukkit.getOnlinePlayers()) {
            if (!recipient.getWorld().equals(sender.getWorld())) continue;
            if (recipient.getLocation().distanceSquared(sender.getLocation()) <= radiusSquared) {
                deliveries.put(recipient.getUniqueId(), local);
            }
        }
        deliveries.putIfAbsent(sender.getUniqueId(), local);

        CallSession connected = calls.connected(sender.getUniqueId()).orElse(null);
        if (connected != null) {
            if (!devices.hasUsable(sender, DeviceType.PHONE)) {
                try { hangup(sender); } catch (RuntimeException ignored) {}
                UiText.error(sender, "Телефон недоступен — звонок завершён. Сообщение услышали только рядом.");
            } else {
                UUID partnerId = connected.partner(sender.getUniqueId());
                Player partner = online(partnerId).orElse(null);
                if (partner != null && devices.hasUsable(partner, DeviceType.PHONE)) {
                    Component remote = phoneMessage(sender, intent);
                    deliveries.put(sender.getUniqueId(), remote);
                    deliveries.put(partnerId, remote);
                    sender.sendActionBar(status("ТЕЛЕФОН • " + partner.getName(), NamedTextColor.AQUA));
                } else {
                    try { hangup(sender); } catch (RuntimeException ignored) {}
                    UiText.error(sender, "Связь потеряна: телефон собеседника недоступен. Сообщение услышали только рядом.");
                }
            }
        } else if (radioMode.contains(sender.getUniqueId())) {
            if (!devices.hasUsable(sender, DeviceType.RADIO)) {
                radioMode.remove(sender.getUniqueId());
                UiText.error(sender, "Рация отсутствует — передача выключена. Сообщение услышали только рядом.");
            } else {
                String channel = channel(sender.getUniqueId());
                Component remote = radioMessage(sender, channel, intent);
                for (Player recipient : Bukkit.getOnlinePlayers()) {
                    if (!radioMode.contains(recipient.getUniqueId())) continue;
                    if (!devices.hasUsable(recipient, DeviceType.RADIO)) continue;
                    if (channel.equals(channel(recipient.getUniqueId()))) deliveries.put(recipient.getUniqueId(), remote);
                }
                deliveries.put(sender.getUniqueId(), remote);
                sender.sendActionBar(status("РАЦИЯ • " + channel, NamedTextColor.GOLD));
                play(sender, plugin.config().communication().radioTransmitSound(), 1.35f);
            }
        }

        deliveries.forEach((playerId, message) -> online(playerId).ifPresent(player -> player.sendMessage(message)));
    }

    public void transmitOnce(Player sender, ChatIntent intent) {
        requireRadio(sender);
        if (!plugin.config().communication().radioEnabled()) throw new IllegalStateException("Радиосвязь отключена.");
        if (calls.connected(sender.getUniqueId()).isPresent()) {
            throw new IllegalStateException("Нельзя передавать в радиоэфир во время телефонного звонка.");
        }
        boolean alreadyEnabled = radioMode.contains(sender.getUniqueId());
        radioMode.add(sender.getUniqueId());
        try { deliver(sender, intent); }
        finally { if (!alreadyEnabled) radioMode.remove(sender.getUniqueId()); }
    }

    public void quit(Player player) {
        radioMode.remove(player.getUniqueId());
        if (calls.call(player.getUniqueId()).isPresent()) {
            try { hangup(player); } catch (RuntimeException ignored) {}
        }
        lastOutgoingCall.remove(player.getUniqueId());
    }

    public void refreshIndicators() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            CallSession call = calls.call(player.getUniqueId()).orElse(null);
            if (call != null) {
                String partner = online(call.partner(player.getUniqueId())).map(Player::getName).orElse("офлайн");
                String label = call.state() == CallState.CONNECTED ? "ТЕЛЕФОН • " + partner
                        : call.targetId().equals(player.getUniqueId()) ? "ВХОДЯЩИЙ ЗВОНОК • " + partner
                        : "ВЫЗОВ • " + partner;
                player.sendActionBar(status(label, call.state() == CallState.CONNECTED ? NamedTextColor.AQUA : NamedTextColor.YELLOW));
            } else if (radioMode.contains(player.getUniqueId())) {
                player.sendActionBar(status("РАЦИЯ • " + channel(player.getUniqueId()), NamedTextColor.GOLD));
            }
        }
    }

    public boolean radioEnabled(UUID playerId) { return radioMode.contains(playerId); }
    public String channel(UUID playerId) {
        return devices.active(playerId, DeviceType.RADIO)
                .map(Device::lastChannel)
                .filter(value -> value != null && !value.isBlank())
                .orElse(plugin.config().communication().defaultRadioChannel());
    }
    public CallSession call(UUID playerId) { return calls.call(playerId).orElse(null); }
    public DeviceService devices() { return devices; }
    public List<String> channels() { return plugin.config().communication().radioChannels(); }

    private void requirePhone(Player player) {
        if (!devices.hasUsable(player, DeviceType.PHONE)) {
            throw new IllegalStateException("Телефон не найден в инвентаре или был отозван.");
        }
    }

    private void requireRadio(Player player) {
        if (!devices.hasUsable(player, DeviceType.RADIO)) {
            throw new IllegalStateException("Рация не найдена в инвентаре или была отозвана.");
        }
    }

    private void sendIncomingCall(Player target, Player caller) {
        Component prefix = Component.text("CityCore", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                .append(text("  •  Входящий звонок от " + caller.getName() + " ", NamedTextColor.YELLOW));
        Component accept = text("[ПРИНЯТЬ]", NamedTextColor.GREEN).decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/phone accept"));
        Component decline = text("  [ОТКЛОНИТЬ]", NamedTextColor.RED).decorate(TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand("/phone decline"));
        target.sendMessage(prefix.append(accept).append(decline));
        target.sendMessage(text("Команды: /pha или /phd", NamedTextColor.DARK_GRAY));
    }

    private Component localMessage(Player sender, ChatIntent intent) {
        NamedTextColor accent = intent.shout() ? NamedTextColor.RED : NamedTextColor.GRAY;
        String verb = intent.shout() ? " кричит" : "";
        return text("[Рядом] ", NamedTextColor.DARK_GRAY)
                .append(sender.displayName().decoration(TextDecoration.ITALIC, false))
                .append(text(verb + ": ", accent))
                .append(text(intent.message(), NamedTextColor.WHITE));
    }

    private Component phoneMessage(Player sender, ChatIntent intent) {
        return text("[Телефон] ", NamedTextColor.AQUA)
                .append(sender.displayName().decoration(TextDecoration.ITALIC, false))
                .append(text(": ", NamedTextColor.DARK_GRAY))
                .append(text(intent.message(), NamedTextColor.WHITE))
                .append(intent.shout() ? text("  <кричит>", NamedTextColor.RED) : Component.empty());
    }

    private Component radioMessage(Player sender, String channel, ChatIntent intent) {
        return text("[Рация • " + channel + "] ", NamedTextColor.GOLD)
                .append(sender.displayName().decoration(TextDecoration.ITALIC, false))
                .append(text(": ", NamedTextColor.DARK_GRAY))
                .append(text(intent.message(), NamedTextColor.WHITE))
                .append(intent.shout() ? text("  <кричит>", NamedTextColor.RED) : Component.empty());
    }

    private Component status(String value, NamedTextColor color) {
        return text(value, color).decorate(TextDecoration.BOLD);
    }

    private void play(Player player, org.bukkit.Sound sound, float pitch) {
        float volume = plugin.config().communication().soundVolume();
        if (volume <= 0) return;
        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    private java.util.Optional<Player> online(UUID playerId) { return java.util.Optional.ofNullable(Bukkit.getPlayer(playerId)); }
    private String phoneSerial(Player player) { return phoneSerial(player.getUniqueId()); }
    private String phoneSerial(UUID playerId) { return devices.active(playerId, DeviceType.PHONE).map(Device::serial).orElse(null); }
    private String radioSerial(Player player) { return devices.active(player.getUniqueId(), DeviceType.RADIO).map(Device::serial).orElse(null); }

    private void audit(String type, UUID actor, UUID peer, String serial, String channel, String details) {
        storage.submit(() -> { repository.log(type, actor, peer, serial, channel, details); return null; })
                .exceptionally(error -> {
                    plugin.getLogger().warning("Не удалось записать событие связи " + type + ": " + error.getMessage());
                    return null;
                });
    }

    private static Component text(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }
}
