package ru.citycore.communication;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.citycore.CityCorePlugin;
import ru.citycore.db.StorageExecutor;
import ru.citycore.gui.UiText;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Main-thread cache and safe provisioning facade over persistent devices. */
public final class DeviceService {
    private final CityCorePlugin plugin;
    private final StorageExecutor storage;
    private final DeviceRepository repository;
    private final DeviceItems items;
    private final Map<UUID, Map<DeviceType, Device>> active = new ConcurrentHashMap<>();

    public DeviceService(CityCorePlugin plugin, StorageExecutor storage, DeviceRepository repository, DeviceItems items) {
        this.plugin = plugin;
        this.storage = storage;
        this.repository = repository;
        this.items = items;
    }

    /** Must be called after the player profile has been committed. */
    public void loadAndProvision(Player player) {
        UUID playerId = player.getUniqueId();
        storage.submit(() -> {
            Map<DeviceType, Device> devices = new EnumMap<>(DeviceType.class);
            if (plugin.config().communication().issueStarterPhone()) {
                devices.put(DeviceType.PHONE, repository.ensureActive(playerId, DeviceType.PHONE,
                        plugin.config().communication().defaultRadioChannel()));
            } else repository.findActive(playerId, DeviceType.PHONE).ifPresent(device -> devices.put(DeviceType.PHONE, device));
            if (plugin.config().communication().issueStarterRadio()) {
                devices.put(DeviceType.RADIO, repository.ensureActive(playerId, DeviceType.RADIO,
                        plugin.config().communication().defaultRadioChannel()));
            } else repository.findActive(playerId, DeviceType.RADIO).ifPresent(device -> devices.put(DeviceType.RADIO, device));
            return devices;
        }).whenComplete((devices, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (error != null) {
                plugin.getLogger().warning("Не удалось загрузить устройства " + player.getName() + ": " + rootMessage(error));
                UiText.error(player, "Устройства связи временно недоступны. Сообщите администратору.");
                return;
            }
            Map<DeviceType, Device> cached = new EnumMap<>(DeviceType.class);
            cached.putAll(devices);
            active.put(playerId, cached);
            giveMissing(player);
        }));
    }

    public void giveMissing(Player player) {
        for (Device device : active.getOrDefault(player.getUniqueId(), Map.of()).values()) {
            if (hasItem(player, device)) continue;
            Map<Integer, ItemStack> overflow = player.getInventory().addItem(items.create(device));
            if (!overflow.isEmpty()) {
                UiText.info(player, "Освободите место: устройство «" + device.model() + "» ожидает выдачи.");
            } else {
                UiText.success(player, device.type() == DeviceType.PHONE
                        ? "Кнопочный телефон добавлен в инвентарь."
                        : "Рация добавлена в инвентарь. Канал: " + device.lastChannel());
            }
        }
    }

    public Optional<Device> active(UUID playerId, DeviceType type) {
        return Optional.ofNullable(active.getOrDefault(playerId, Map.of()).get(type));
    }

    public boolean hasUsable(Player player, DeviceType type) {
        Device device = active(player.getUniqueId(), type).orElse(null);
        return device != null && hasItem(player, device);
    }

    public boolean isUsable(Player player, ItemStack item) {
        DeviceType type = items.type(item);
        if (type == null) return false;
        return items.matches(item, active(player.getUniqueId(), type).orElse(null));
    }

    public void setChannel(Player player, String channel, java.util.function.Consumer<Device> success) {
        storage.submit(() -> repository.setChannel(player.getUniqueId(), channel)).whenComplete((device, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (error != null) {
                        UiText.error(player, "Канал не изменён: " + rootMessage(error));
                        return;
                    }
                    active.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>())
                            .put(DeviceType.RADIO, device);
                    refreshInventoryItem(player, device);
                    success.accept(device);
                }));
    }

    public void unload(UUID playerId) { active.remove(playerId); }

    public DeviceItems items() { return items; }

    private boolean hasItem(Player player, Device device) {
        for (ItemStack item : player.getInventory().getContents()) if (items.matches(item, device)) return true;
        return items.matches(player.getInventory().getItemInOffHand(), device);
    }

    private void refreshInventoryItem(Player player, Device device) {
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (items.matches(contents[slot], device)) player.getInventory().setItem(slot, items.create(device));
        }
        if (items.matches(player.getInventory().getItemInOffHand(), device)) {
            player.getInventory().setItemInOffHand(items.create(device));
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
