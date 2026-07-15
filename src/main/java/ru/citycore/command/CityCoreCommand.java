package ru.citycore.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import ru.citycore.CityCorePlugin;
import ru.citycore.city.CityRole;
import ru.citycore.city.CityService;
import ru.citycore.business.BusinessService;
import ru.citycore.economy.VaultTransferCoordinator;
import ru.citycore.economy.MinorUnits;
import ru.citycore.db.StorageExecutor;
import ru.citycore.gui.GuiService;
import ru.citycore.gui.Route;
import ru.citycore.gui.UiText;

import java.util.List;
import java.util.UUID;

public final class CityCoreCommand implements CommandExecutor, TabCompleter {
    private final CityCorePlugin plugin; private final GuiService gui; private final StorageExecutor storage; private final CityService cities; private final BusinessService businesses; private final VaultTransferCoordinator vaultTransfers;
    public CityCoreCommand(CityCorePlugin plugin, GuiService gui, StorageExecutor storage, CityService cities, BusinessService businesses, VaultTransferCoordinator vaultTransfers) {
        this.plugin = plugin; this.gui = gui; this.storage = storage; this.cities = cities; this.businesses = businesses; this.vaultTransfers = vaultTransfers;
    }
    @Override public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) gui.open(player, Route.HOME);
            else UiText.info(sender, "Использование из консоли: /cc status");
            return true;
        }
        if (args[0].equalsIgnoreCase("status") && sender.hasPermission("citycore.admin.status")) {
            UiText.success(sender, plugin.getPluginMeta().getVersion() + " · база данных доступна · Vault: " + (plugin.economy().available() ? "подключён" : "недоступен"));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload") && sender.hasPermission("citycore.admin.reload")) {
            try {
                plugin.reloadCityCoreConfig(); UiText.success(sender, "Настройки перечитаны.");
            } catch (RuntimeException error) { UiText.error(sender, "Настройки не применены: " + error.getMessage()); }
            return true;
        }
        if (args[0].equalsIgnoreCase("city") && args.length >= 4 && args[1].equalsIgnoreCase("create") && sender instanceof Player player) {
            String name = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
            UiText.info(sender, "Создаём город…");
            storage.submit(() -> cities.create(player.getUniqueId(), args[2], name)).whenComplete((city, error) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (error != null) UiText.error(player, "Город не создан: " + rootMessage(error));
                        else UiText.success(player, "Город «" + city.name() + "» создан. Вы назначены мэром.");
                    }));
            return true;
        }
        if (args[0].equalsIgnoreCase("city") && args.length == 3 && args[1].equalsIgnoreCase("apply") && sender instanceof Player player) {
            runAsync(player, () -> { cities.apply(player.getUniqueId(), args[2]); return "Заявка отправлена."; }); return true;
        }
        if (args[0].equalsIgnoreCase("city") && args.length == 2 && args[1].equalsIgnoreCase("info") && sender instanceof Player player) {
            storage.submit(() -> cities.view(player.getUniqueId())).whenComplete((view, error) -> sync(player, () -> {
                if (error != null) failure(player, error);
                else if (view == null) UiText.info(player, "У вас пока нет гражданства.");
                else UiText.info(player, view.name() + " [" + view.slug() + "] · должность: " + roleLabel(view.role())
                        + " · казна: " + MinorUnits.format(view.treasuryMinor(), plugin.config().currencyScale()));
            })); return true;
        }
        if (args[0].equalsIgnoreCase("city") && args.length == 2 && args[1].equalsIgnoreCase("applications") && sender instanceof Player player) {
            storage.submit(() -> cities.pending(player.getUniqueId())).whenComplete((items, error) -> sync(player, () -> {
                if (error != null) failure(player, error);
                else if (items.isEmpty()) UiText.info(player, "Ожидающих заявок нет.");
                else items.forEach(item -> UiText.info(player, item.playerName() + " · " + item.playerId()));
            })); return true;
        }
        if (args[0].equalsIgnoreCase("city") && args.length == 3 && (args[1].equalsIgnoreCase("accept") || args[1].equalsIgnoreCase("reject")) && sender instanceof Player player) {
            try {
                UUID applicant = UUID.fromString(args[2]); boolean accept = args[1].equalsIgnoreCase("accept");
                runAsync(player, () -> { cities.decide(player.getUniqueId(), applicant, accept); return accept ? "Заявка принята." : "Заявка отклонена."; });
            } catch (IllegalArgumentException invalid) { UiText.error(player, "Укажите UUID из списка /cc city applications."); }
            return true;
        }
        if (args[0].equalsIgnoreCase("city") && args.length == 4 && args[1].equalsIgnoreCase("role") && sender instanceof Player player) {
            try {
                UUID member = UUID.fromString(args[2]);
                ru.citycore.city.CityRole role = switch (args[3].toLowerCase(java.util.Locale.ROOT)) {
                    case "citizen" -> ru.citycore.city.CityRole.CITIZEN;
                    case "official" -> ru.citycore.city.CityRole.OFFICIAL;
                    default -> throw new IllegalArgumentException();
                };
                runAsync(player, () -> { cities.setRole(player.getUniqueId(), member, role); return "Новая должность: " + roleLabel(role) + "."; });
            } catch (IllegalArgumentException invalid) { UiText.error(player, "Формат: /cc city role <UUID> <citizen|official>"); }
            return true;
        }
        if (args[0].equalsIgnoreCase("business") && args.length >= 4 && args[1].equalsIgnoreCase("register") && sender instanceof Player player) {
            String name = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
            runAsync(player, () -> "Бизнес зарегистрирован: " + businesses.register(player.getUniqueId(), args[2], name).id()); return true;
        }
        if (args[0].equalsIgnoreCase("business") && args.length == 3 && args[1].equalsIgnoreCase("info") && sender instanceof Player player) {
            storage.submit(() -> businesses.detail(player.getUniqueId(), args[2])).whenComplete((business, error) -> sync(player, () -> {
                if (error != null) failure(player, error);
                else UiText.info(player, business.name() + " · " + statusLabel(business.status())
                        + " · владелец: " + business.ownerName()
                        + (business.balanceMinor() == null ? "" : " · счёт: " + MinorUnits.format(business.balanceMinor(), plugin.config().currencyScale())));
            })); return true;
        }
        if (args[0].equalsIgnoreCase("business") && args.length == 4 && args[1].equalsIgnoreCase("deposit") && sender instanceof Player player) {
            try {
                vaultTransfers.depositToBusiness(player, args[2], MinorUnits.parse(args[3], plugin.config().currencyScale()));
            } catch (ArithmeticException | NumberFormatException invalid) {
                UiText.error(player, "Некорректная сумма. Пример: /cc business deposit <ID> 100.00");
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("business") && args.length == 2 && (args[1].equalsIgnoreCase("list") || args[1].equalsIgnoreCase("pending")) && sender instanceof Player player) {
            boolean pending = args[1].equalsIgnoreCase("pending");
            storage.submit(() -> businesses.list(player.getUniqueId(), pending)).whenComplete((items, error) -> sync(player, () -> {
                if (error != null) failure(player, error);
                else if (items.isEmpty()) UiText.info(player, "В городе пока нет зарегистрированных бизнесов.");
                else items.forEach(item -> UiText.info(player, item.name() + " · " + statusLabel(item.status()) + " · ID: " + item.id()));
            })); return true;
        }
        if (args[0].equalsIgnoreCase("business") && args.length == 3 && (args[1].equalsIgnoreCase("approve") || args[1].equalsIgnoreCase("reject")) && sender instanceof Player player) {
            boolean approve = args[1].equalsIgnoreCase("approve");
            runAsync(player, () -> { businesses.decide(player.getUniqueId(), args[2], approve); return approve ? "Бизнес одобрен." : "Бизнес отклонён."; }); return true;
        }
        if (args[0].equalsIgnoreCase("business") && args.length == 5 && args[1].equalsIgnoreCase("license") && sender instanceof Player player) {
            try {
                int days = Integer.parseInt(args[4]);
                runAsync(player, () -> "Лицензия выдана: " + businesses.issueLicense(player.getUniqueId(), args[2], args[3], days).id());
            } catch (NumberFormatException invalid) { UiText.error(player, "Срок лицензии необходимо указать в днях."); }
            return true;
        }
        if (args[0].equalsIgnoreCase("business") && args.length == 4 && args[1].equalsIgnoreCase("unlicense") && sender instanceof Player player) {
            runAsync(player, () -> { businesses.revokeLicense(player.getUniqueId(), args[2], args[3]); return "Лицензия отозвана."; }); return true;
        }
        if (args[0].equalsIgnoreCase("treasury") && args.length == 3 && args[1].equalsIgnoreCase("deposit") && sender instanceof Player player) {
            try {
                long minor = MinorUnits.parse(args[2], plugin.config().currencyScale());
                vaultTransfers.depositToTreasury(player, minor);
            } catch (ArithmeticException | NumberFormatException invalid) {
                UiText.error(player, "Некорректная сумма. Пример: /cc treasury deposit 100.00");
            }
            return true;
        }
        UiText.info(sender, "Доступно: /cc city …, /cc business …, /cc treasury …, /cc status");
        return true;
    }
    @Override public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) return List.of("city", "business", "treasury", "status", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("city")) return List.of("create", "apply", "info", "applications", "accept", "reject", "role");
        if (args.length == 2 && args[0].equalsIgnoreCase("business")) return List.of("register", "info", "deposit", "list", "pending", "approve", "reject", "license", "unlicense");
        if (args.length == 2 && args[0].equalsIgnoreCase("treasury")) return List.of("deposit");
        return List.of();
    }
    private String rootMessage(Throwable error) {
        Throwable current = error; while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
    private void runAsync(Player player, java.util.concurrent.Callable<String> operation) {
        storage.submit(() -> { try { return operation.call(); } catch (Exception error) { throw new RuntimeException(error); } })
                .whenComplete((message, error) -> sync(player, () -> {
                    if (error != null) failure(player, error); else UiText.success(player, message);
                }));
    }
    private void sync(Player player, Runnable action) { plugin.getServer().getScheduler().runTask(plugin, () -> { if (player.isOnline()) action.run(); }); }
    private void failure(Player player, Throwable error) { UiText.error(player, "Операция не выполнена: " + rootMessage(error)); }
    private String roleLabel(CityRole role) { return switch (role) {
        case CITIZEN -> "житель"; case OFFICIAL -> "городской чиновник"; case MAYOR -> "мэр";
    }; }
    private String statusLabel(String status) { return switch (status) {
        case "ACTIVE" -> "активен"; case "PENDING" -> "ожидает рассмотрения";
        case "REJECTED" -> "отклонён"; case "SUSPENDED" -> "приостановлен";
        case "REVOKED" -> "отозван"; default -> status.toLowerCase(java.util.Locale.ROOT);
    }; }
}
