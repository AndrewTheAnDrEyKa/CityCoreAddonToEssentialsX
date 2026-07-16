package ru.citycore.industry;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import ru.citycore.CityCorePlugin;
import ru.citycore.db.StorageExecutor;
import ru.citycore.gui.GuiService;
import ru.citycore.gui.UiText;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps the world representation synchronized with database controller bindings. */
public final class ControllerListener implements Listener {
    private static final UUID SYSTEM_ACTOR = new UUID(0L, 0L);
    private final CityCorePlugin plugin;
    private final StorageExecutor storage;
    private final IndustryService industry;
    private final ControllerItems items;
    private final GuiService gui;
    private final Material material;
    private final Map<BlockKey, String> locations = new ConcurrentHashMap<>();

    public ControllerListener(CityCorePlugin plugin, StorageExecutor storage, IndustryService industry,
                              ControllerItems items, GuiService gui) {
        this.plugin = plugin; this.storage = storage; this.industry = industry; this.items = items; this.gui = gui;
        this.material = plugin.config().industry().controllerMaterial();
    }

    public void start() {
        storage.submit(industry::activeControllerLocations).whenComplete((values, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().warning("Не удалось загрузить контроллеры: " + rootMessage(error));
                        return;
                    }
                    locations.clear();
                    for (IndustryService.ControllerLocation value : values) {
                        locations.put(new BlockKey(value.worldId(), value.x(), value.y(), value.z()), value.serial());
                    }
                }));
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::verifyBlocks, 200L, 200L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        String serial = items.serial(event.getItemInHand());
        if (serial == null) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        if (!event.getBlockReplacedState().getType().isAir()) {
            UiText.error(player, "Контроллер можно установить только в свободный блок.");
            return;
        }
        ItemStack original = event.getItemInHand().clone(); original.setAmount(1);
        consume(player, event.getHand());
        UUID worldId = block.getWorld().getUID(); String worldName = block.getWorld().getName();
        int x = block.getX(), y = block.getY(), z = block.getZ();
        storage.submit(() -> industry.placeController(player.getUniqueId(), serial, worldId, worldName, x, y, z))
                .whenComplete((value, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        returnItem(player, original);
                        UiText.error(player, "Контроллер не установлен: " + rootMessage(error));
                        return;
                    }
                    if (!block.getType().isAir()) {
                        storage.submit(() -> industry.removeController(player.getUniqueId(), serial, false));
                        returnItem(player, original);
                        UiText.error(player, "Место установки успело измениться. Контроллер возвращён.");
                        return;
                    }
                    block.setType(material, false);
                    locations.put(new BlockKey(worldId, x, y, z), serial);
                    UiText.success(player, "Контроллер установлен. Участок создан автоматически, заявка на осмотр отправлена. Нажмите ПКМ по блоку.");
                }));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock(); String serial = locations.get(BlockKey.of(block));
        if (serial == null) return;
        event.setCancelled(true); event.setDropItems(false);
        Player player = event.getPlayer(); boolean administrator = player.hasPermission("citycore.admin.industry");
        storage.submit(() -> industry.removeController(player.getUniqueId(), serial, administrator))
                .whenComplete((value, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (error != null) { UiText.error(player, "Контроллер не снят: " + rootMessage(error)); return; }
                    locations.remove(BlockKey.of(block)); block.setType(Material.AIR, false);
                    if ("RETIRED".equals(value.state())) {
                        UiText.success(player, "Выведенный из эксплуатации контроллер демонтирован. Теперь можно получить новый для другого участка.");
                    } else {
                        returnItem(player, items.create(material, value.serial(), value.businessName()));
                        UiText.success(player, "Контроллер снят. Производство временно остановлено; верните его в исходную точку для продолжения.");
                    }
                }));
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getHand() != EquipmentSlot.HAND) return;
        String serial = locations.get(BlockKey.of(event.getClickedBlock()));
        if (serial == null) return;
        event.setCancelled(true);
        gui.openController(event.getPlayer(), serial);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplosion(EntityExplodeEvent event) { removeExploded(event.blockList()); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplosion(BlockExplodeEvent event) { removeExploded(event.blockList()); }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> locations.containsKey(BlockKey.of(block)))) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (event.getBlocks().stream().anyMatch(block -> locations.containsKey(BlockKey.of(block)))) event.setCancelled(true);
    }

    private void removeExploded(java.util.List<Block> blocks) {
        blocks.removeIf(block -> {
            String serial = locations.remove(BlockKey.of(block));
            if (serial == null) return false;
            block.setType(Material.AIR, false);
            storage.submit(() -> industry.removeController(SYSTEM_ACTOR, serial, true));
            return true;
        });
    }

    private void verifyBlocks() {
        for (Map.Entry<BlockKey, String> entry : Map.copyOf(locations).entrySet()) {
            World world = plugin.getServer().getWorld(entry.getKey().worldId());
            if (world == null || !world.isChunkLoaded(entry.getKey().x() >> 4, entry.getKey().z() >> 4)) continue;
            Block block = world.getBlockAt(entry.getKey().x(), entry.getKey().y(), entry.getKey().z());
            if (block.getType() == material) continue;
            locations.remove(entry.getKey());
            storage.submit(() -> industry.removeController(SYSTEM_ACTOR, entry.getValue(), true));
        }
    }

    private void consume(Player player, EquipmentSlot hand) {
        ItemStack held = hand == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        if (held.getAmount() <= 1) held.setAmount(0); else held.setAmount(held.getAmount() - 1);
    }

    private void returnItem(Player player, ItemStack item) {
        Map<Integer, ItemStack> remaining = player.getInventory().addItem(item);
        remaining.values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }

    private String rootMessage(Throwable error) {
        Throwable current = error; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey of(Block block) { return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()); }
    }
}
