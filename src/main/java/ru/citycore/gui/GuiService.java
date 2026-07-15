package ru.citycore.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import ru.citycore.CityCorePlugin;
import ru.citycore.city.CityRole;
import ru.citycore.city.CityService;
import ru.citycore.business.BusinessService;
import ru.citycore.db.StorageExecutor;
import ru.citycore.economy.EconomyGateway;
import ru.citycore.economy.MinorUnits;
import ru.citycore.economy.VaultTransferCoordinator;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GuiService {
    public static final int BACK_SLOT = 45, HOME_SLOT = 49, CLOSE_SLOT = 53;
    private final CityCorePlugin plugin;
    private final EconomyGateway economy;
    private final StorageExecutor storage;
    private final CityService cities;
    private final BusinessService businesses;
    private final ChatPromptService prompts;
    private final GuiFeedback feedback;
    private final VaultTransferCoordinator vaultTransfers;
    private final NamespacedKey actionKey;
    private final Map<UUID, Route> context = new ConcurrentHashMap<>();

    public GuiService(CityCorePlugin plugin, EconomyGateway economy, StorageExecutor storage, CityService cities,
                      BusinessService businesses, ChatPromptService prompts, GuiFeedback feedback,
                      VaultTransferCoordinator vaultTransfers) {
        this.plugin = plugin; this.economy = economy; this.storage = storage; this.cities = cities; this.businesses = businesses;
        this.prompts = prompts; this.feedback = feedback; this.vaultTransfers = vaultTransfers;
        this.actionKey = new NamespacedKey(plugin, "gui_action");
    }

    public void open(Player player, Route route) {
        context.put(player.getUniqueId(), route);
        CityCoreHolder holder = new CityCoreHolder(route);
        Inventory inventory = plugin.getServer().createInventory(holder, 54,
                Component.text(title(route), NamedTextColor.DARK_GRAY));
        holder.bind(inventory);
        render(player, route, inventory);
        player.openInventory(inventory);
        feedback.open(player);
    }

    private void render(Player player, Route route, Inventory inventory) {
        decorate(inventory);
        if (route == Route.HOME) {
            inventory.setItem(19, button(Material.NAME_TAG, "Профиль", "route:PROFILE"));
            inventory.setItem(21, button(Material.BELL, "Город", "route:CITY"));
            inventory.setItem(23, button(Material.BRICKS, "Бизнесы", "route:BUSINESS"));
            inventory.setItem(25, button(Material.GOLD_INGOT, "Экономика", "route:ECONOMY"));
            if (player.hasPermission("citycore.admin")) inventory.setItem(31, button(Material.COMMAND_BLOCK, "Администрирование", "route:ADMIN"));
        } else if (route == Route.PROFILE) {
            inventory.setItem(22, info(Material.NAME_TAG, player.getName(), "UUID: " + player.getUniqueId()));
        } else if (route == Route.CITY) {
            inventory.setItem(22, info(Material.CLOCK, "Загрузка города…"));
            storage.submit(() -> cities.view(player.getUniqueId())).whenComplete((view, error) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline() || player.getOpenInventory().getTopInventory() != inventory) return;
                        if (error != null) inventory.setItem(22, info(Material.BARRIER, "Ошибка загрузки", rootMessage(error)));
                        else if (view == null) {
                            inventory.setItem(20, button(Material.WRITABLE_BOOK, "Подать заявку", "prompt:city_apply", "Укажите ID города в чате"));
                            inventory.setItem(24, button(Material.BELL, "Основать город", "prompt:city_create", "Создание в два коротких шага"));
                            inventory.setItem(22, info(Material.MAP, "Нет гражданства", "Вы пока не состоите в городе"));
                        } else {
                            inventory.setItem(20, info(Material.BELL, view.name(), "ID: " + view.slug(), "Роль: " + view.role()));
                            inventory.setItem(24, info(Material.GOLD_INGOT, "Казначейство", formatMinor(view.treasuryMinor())));
                            inventory.setItem(22, info(Material.MAP, "Статус", view.status()));
                            if (view.role() == CityRole.MAYOR) inventory.setItem(31, button(Material.WRITABLE_BOOK, "Заявки на гражданство", "command:city applications"));
                        }
                    }));
        } else if (route == Route.BUSINESS) {
            inventory.setItem(22, info(Material.CLOCK, "Загрузка бизнесов…"));
            storage.submit(() -> businesses.list(player.getUniqueId(), false)).whenComplete((items, error) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline() || player.getOpenInventory().getTopInventory() != inventory) return;
                        inventory.setItem(22, null);
                        if (error != null) inventory.setItem(22, info(Material.BARRIER, "Ошибка загрузки", rootMessage(error)));
                        else if (items.isEmpty()) inventory.setItem(22, button(Material.BRICKS, "Зарегистрировать бизнес", "prompt:business_register", "ID и название вводятся в чате"));
                        else {
                            int[] slots = {19,20,21,22,23,24,25}; int index = 0;
                            for (var business : items) {
                                if (index >= slots.length) break;
                                inventory.setItem(slots[index++], info(Material.BRICKS, business.name(), "ID: " + business.id(), "Статус: " + business.status()));
                            }
                        }
                        inventory.setItem(31, button(Material.WRITABLE_BOOK, "Заявки на регистрацию", "command:business pending"));
                    }));
        } else if (route == Route.ECONOMY) {
            long balance = economy.balanceMinor(player.getUniqueId());
            inventory.setItem(22, info(Material.GOLD_INGOT, "Личный баланс", formatMinor(balance)));
            inventory.setItem(31, button(Material.HOPPER, "Пополнить казначейство", "prompt:treasury_deposit", "Перевод из EssentialsX Economy"));
        } else if (route == Route.ADMIN) {
            inventory.setItem(22, info(Material.COMPARATOR, "Состояние", "Используйте /cc status"));
        }
        if (route != Route.HOME) inventory.setItem(BACK_SLOT, button(Material.ARROW, "Назад", "back"));
        inventory.setItem(HOME_SLOT, button(Material.COMPASS, "Главная", "route:HOME"));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "Закрыть", "close"));
    }

    public String action(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    public void handle(Player player, Route current, String action) {
        if (action == null) return;
        feedback.click(player);
        if (action.equals("close")) player.closeInventory();
        else if (action.equals("back") || action.equals("route:HOME")) open(player, Route.HOME);
        else if (action.startsWith("route:")) {
            Route target;
            try { target = Route.valueOf(action.substring(6)); } catch (IllegalArgumentException ignored) { return; }
            if (target == Route.ADMIN && !player.hasPermission("citycore.admin")) {
                player.sendMessage(Component.text("Недостаточно прав.", NamedTextColor.RED)); return;
            }
            open(player, target);
        } else if (action.startsWith("command:")) {
            player.closeInventory(); player.performCommand("cc " + action.substring(8));
        } else if (action.equals("prompt:city_apply")) {
            prompts.begin(player, "Введите ID города:", slug -> execute(player,
                    () -> { cities.apply(player.getUniqueId(), slug); return "Заявка отправлена."; }, Route.CITY));
        } else if (action.equals("prompt:city_create")) {
            prompts.begin(player, "Придумайте системный ID города (a-z, 0-9, _ или -):", slug ->
                    prompts.begin(player, "Введите отображаемое название города:", name -> execute(player,
                            () -> { cities.create(player.getUniqueId(), slug, name); return "Город «" + name + "» создан."; }, Route.CITY)));
        } else if (action.equals("prompt:business_register")) {
            prompts.begin(player, "Придумайте системный ID бизнеса:", slug ->
                    prompts.begin(player, "Введите название бизнеса:", name -> execute(player,
                            () -> { businesses.register(player.getUniqueId(), slug, name); return "Заявка на регистрацию отправлена."; }, Route.BUSINESS)));
        } else if (action.equals("prompt:treasury_deposit")) {
            prompts.begin(player, "Введите сумму пополнения:", value -> {
                try { vaultTransfers.depositToTreasury(player, MinorUnits.parse(value, plugin.config().currencyScale())); }
                catch (RuntimeException error) { failure(player, error); }
            });
        }
    }

    private ItemStack button(Material material, String name, String action) {
        return button(material, name, action, "Нажмите, чтобы открыть");
    }
    private ItemStack button(Material material, String name, String action, String... lore) {
        ItemStack item = info(material, name, lore);
        item.editMeta(meta -> meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action));
        return item;
    }
    private ItemStack info(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(Component.text(name, NamedTextColor.GOLD));
            meta.lore(java.util.Arrays.stream(lore).map(line -> Component.text(line, NamedTextColor.GRAY)).toList());
        });
        return item;
    }
    private void decorate(Inventory inventory) {
        ItemStack border = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        border.editMeta(meta -> meta.displayName(Component.empty()));
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9, column = slot % 9;
            if (row == 0 || row == 5 || column == 0 || column == 8) inventory.setItem(slot, border);
        }
    }
    private void execute(Player player, java.util.concurrent.Callable<String> operation, Route returnRoute) {
        storage.submit(() -> { try { return operation.call(); } catch (Exception error) { throw new RuntimeException(error); } })
                .whenComplete((message, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (error != null) failure(player, error);
                    else { feedback.success(player); player.sendMessage(Component.text(message, NamedTextColor.GREEN)); open(player, returnRoute); }
                }));
    }
    private void failure(Player player, Throwable error) {
        feedback.failure(player); player.sendMessage(Component.text("Операция не выполнена: " + rootMessage(error), NamedTextColor.RED));
    }
    private String formatMinor(long amount) { return MinorUnits.format(amount, plugin.config().currencyScale()); }
    private String title(Route route) { return switch (route) {
        case HOME -> "CityCore · Главная"; case PROFILE -> "CityCore · Профиль";
        case CITY -> "CityCore · Город"; case BUSINESS -> "CityCore · Бизнесы"; case ECONOMY -> "CityCore · Экономика";
        case ADMIN -> "CityCore · Администрирование";
    }; }
    private String rootMessage(Throwable error) {
        Throwable current = error; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
