package ru.citycore.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import ru.citycore.CityCorePlugin;
import ru.citycore.business.BusinessService;
import ru.citycore.city.CityRole;
import ru.citycore.city.CityService;
import ru.citycore.db.StorageExecutor;
import ru.citycore.economy.EconomyGateway;
import ru.citycore.economy.MinorUnits;
import ru.citycore.economy.VaultTransferCoordinator;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;

public final class GuiService {
    public static final int BACK_SLOT = 45, HOME_SLOT = 49, CLOSE_SLOT = 53;
    private static final int[] LIST_SLOTS = {
            10, 11, 12, 13, 14, 15, 16,
            19, 20, 21, 22, 23, 24, 25,
            28, 29, 30, 31, 32, 33, 34,
            37, 38, 39, 40, 41, 42, 43
    };
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            .withZone(ZoneId.systemDefault());
    private static final Map<String, PlannedModule> PLANNED = Map.ofEntries(
            Map.entry("notifications", new PlannedModule(Material.BELL, "Уведомления", "Живой рынок",
                    "Очередь решений, платежей и событий игрока.")),
            Map.entry("documents", new PlannedModule(Material.PAPER, "Документы", "Вертикальное ядро",
                    "Удостоверения, лицензии и проверяемые копии.")),
            Map.entry("organizations", new PlannedModule(Material.SHIELD, "Организации", "Собственность и работа",
                    "Городские службы и игровые должности.")),
            Map.entry("market", new PlannedModule(Material.EMERALD, "Магазины и рынок", "Живой рынок",
                    "Товары, склад, покупки и фоновый спрос.")),
            Map.entry("property", new PlannedModule(Material.OAK_DOOR, "Недвижимость", "Собственность и работа",
                    "Адреса, участки, помещения и аренда.")),
            Map.entry("delivery", new PlannedModule(Material.CHEST_MINECART, "Заказы и доставка", "Живой рынок",
                    "Почтовые ящики и безопасная выдача заказов.")),
            Map.entry("services", new PlannedModule(Material.REDSTONE_TORCH, "Городские службы", "Право и преступность",
                    "Обращения в полицию, суд и экстренные службы.")),
            Map.entry("taxes", new PlannedModule(Material.WRITABLE_BOOK, "Налоги и бюджет", "Вертикальное ядро",
                    "Ставки, расчётные периоды и бюджетные лимиты.")),
            Map.entry("laws", new PlannedModule(Material.BOOK, "Законы города", "Вертикальное ядро",
                    "Документы законов и исполнимые параметры.")),
            Map.entry("audit", new PlannedModule(Material.SPYGLASS, "Аудит", "Вертикальное ядро",
                    "Неизменяемая история решений и операций.")),
            Map.entry("objects", new PlannedModule(Material.PISTON, "Объекты и ресурсы", "Вертикальное ядро",
                    "Контроллеры производств и расчётные циклы.")),
            Map.entry("roadmap", new PlannedModule(Material.FILLED_MAP, "Карта развития", "alpha.16",
                    "Сейчас расширяется вертикальное ядро CityCore."))
    );

    private final CityCorePlugin plugin;
    private final EconomyGateway economy;
    private final StorageExecutor storage;
    private final CityService cities;
    private final BusinessService businesses;
    private final ChatPromptService prompts;
    private final GuiFeedback feedback;
    private final VaultTransferCoordinator vaultTransfers;
    private final NamespacedKey actionKey;
    private final Map<UUID, String> selectedBusiness = new ConcurrentHashMap<>();
    private final Map<UUID, CityService.Application> selectedApplication = new ConcurrentHashMap<>();
    private final Map<UUID, PlannedModule> selectedPlanned = new ConcurrentHashMap<>();
    private final Map<UUID, Confirmation> confirmations = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Route, Route>> returnRoutes = new ConcurrentHashMap<>();

    public GuiService(CityCorePlugin plugin, EconomyGateway economy, StorageExecutor storage, CityService cities,
                      BusinessService businesses, ChatPromptService prompts, GuiFeedback feedback,
                      VaultTransferCoordinator vaultTransfers) {
        this.plugin = plugin;
        this.economy = economy;
        this.storage = storage;
        this.cities = cities;
        this.businesses = businesses;
        this.prompts = prompts;
        this.feedback = feedback;
        this.vaultTransfers = vaultTransfers;
        this.actionKey = new NamespacedKey(plugin, "gui_action");
    }

    public void open(Player player, Route route) {
        open(player, route, true);
    }

    private void navigate(Player player, Route route) {
        open(player, route, false);
    }

    private void open(Player player, Route route, boolean openingSound) {
        if (route == Route.ADMIN && !player.hasPermission("citycore.admin")) {
            UiText.error(player, "У вас нет доступа к управлению системой.");
            route = Route.HOME;
        }
        CityCoreHolder holder = new CityCoreHolder(route);
        Inventory inventory = plugin.getServer().createInventory(holder, 54,
                Component.text(title(player, route), NamedTextColor.DARK_GRAY));
        holder.bind(inventory);
        render(player, route, inventory);
        player.openInventory(inventory);
        if (openingSound) feedback.open(player);
    }

    private void render(Player player, Route route, Inventory inventory) {
        decorate(inventory);
        switch (route) {
            case HOME -> renderHome(player, inventory);
            case PROFILE -> renderProfile(player, inventory);
            case CITY -> renderCity(player, inventory);
            case CITY_DIRECTORY -> renderCityDirectory(player, inventory);
            case CITY_APPLICATIONS -> renderCityApplications(player, inventory);
            case CITY_APPLICATION_DETAIL -> renderCityApplication(player, inventory);
            case CITY_MEMBERS -> renderCityMembers(player, inventory);
            case BUSINESS -> renderBusinesses(player, inventory, false);
            case BUSINESS_PENDING -> renderBusinesses(player, inventory, true);
            case BUSINESS_DETAIL -> renderBusinessDetail(player, inventory);
            case LICENSES -> renderLicenses(player, inventory);
            case ECONOMY -> renderEconomy(player, inventory);
            case ADMIN -> renderAdmin(inventory);
            case PLANNED -> renderPlanned(player, inventory);
            case CONFIRM -> renderConfirmation(player, inventory);
        }
        renderNavigation(player, route, inventory);
    }

    private void renderHome(Player player, Inventory inventory) {
        inventory.setItem(4, info(Material.NETHER_STAR, "CityCore · Городская сеть",
                "Ваши города, предприятия и общественные системы", "alpha.16 · вертикальное ядро"));
        inventory.setItem(10, button(Material.PLAYER_HEAD, "Мой профиль", "route:PROFILE",
                "Гражданство, должность и личный баланс"));
        inventory.setItem(12, button(Material.BELL, "Город", "route:CITY",
                "Жители, заявки, казна и управление"));
        inventory.setItem(14, button(Material.BRICKS, "Предприятия", "route:BUSINESS",
                "Регистрация, решения и счета компаний"));
        inventory.setItem(16, button(Material.GOLD_INGOT, "Финансы", "route:ECONOMY",
                "Личный кошелёк и внутренние счета"));

        inventory.setItem(19, button(Material.ENCHANTED_BOOK, "Лицензии", "route:LICENSES",
                "Разрешения ваших предприятий и города"));
        inventory.setItem(21, plannedButton("notifications"));
        inventory.setItem(23, plannedButton("documents"));
        inventory.setItem(25, plannedButton("organizations"));

        inventory.setItem(28, plannedButton("market"));
        inventory.setItem(30, plannedButton("property"));
        inventory.setItem(32, plannedButton("delivery"));
        inventory.setItem(34, plannedButton("services"));

        inventory.setItem(40, plannedButton("roadmap"));
        if (player.hasPermission("citycore.admin")) {
            inventory.setItem(42, button(Material.COMMAND_BLOCK, "Управление системой", "route:ADMIN",
                    "Диагностика, аудит и безопасные настройки"));
        } else {
            inventory.setItem(42, info(Material.CLOCK, "Состояние проекта",
                    "Работает вертикальное ядро alpha.16", "Следующий этап: объекты, налоги и аудит"));
        }
    }

    private void renderProfile(Player player, Inventory inventory) {
        inventory.setItem(13, playerHead(player.getUniqueId(), player.getName(), null,
                "Игровой профиль CityCore", "UUID хранится как основной идентификатор"));
        inventory.setItem(31, info(Material.GOLD_INGOT, "Личный кошелёк",
                formatMinor(economy.balanceMinor(player.getUniqueId())), "Источник: EssentialsX Economy"));
        inventory.setItem(22, loading("Загрузка связей профиля…"));
        storage.submit(() -> new ProfileData(cities.view(player.getUniqueId()), businesses.list(player.getUniqueId(), false)))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    if (error != null) {
                        inventory.setItem(22, errorItem(error));
                        return;
                    }
                    if (data.city() == null) {
                        inventory.setItem(20, info(Material.MAP, "Без гражданства",
                                "Вы пока не состоите в городе", "Открыть варианты можно в разделе «Город»"));
                    } else {
                        inventory.setItem(20, button(Material.BELL, data.city().name(), "route:CITY",
                                "Должность: " + roleLabel(data.city().role()), "Город: " + data.city().slug()));
                    }
                    long owned = data.businesses().stream().filter(item -> item.ownerId().equals(player.getUniqueId())).count();
                    inventory.setItem(24, button(Material.BRICKS, "Мои предприятия", "route:BUSINESS",
                            "Зарегистрировано: " + owned, "Открыть деловой раздел"));
                    inventory.setItem(22, info(Material.CLOCK, "Профиль активен",
                            "Все связи сохранены по UUID", "Последний вход: сейчас"));
                }));
    }

    private void renderCity(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Загрузка городского профиля…"));
        storage.submit(() -> new CityScreenData(cities.view(player.getUniqueId()), cities.applications(player.getUniqueId())))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    if (error != null) {
                        inventory.setItem(22, errorItem(error));
                        return;
                    }
                    if (data.city() == null) {
                        inventory.setItem(13, info(Material.MAP, "Город не выбран",
                                "Гражданство оформляется через заявление", "Одновременно доступен один город"));
                        inventory.setItem(20, button(Material.COMPASS, "Каталог городов", "route:CITY_DIRECTORY",
                                "Выбрать активный город и подать заявление"));
                        inventory.setItem(24, button(Material.BELL, "Основать город", "prompt:city_create",
                                "Создать город и стать его первым мэром"));
                        int index = 0;
                        for (CityService.PlayerApplication application : data.applications()) {
                            if (index >= 5) break;
                            inventory.setItem(29 + index++, info(Material.WRITABLE_BOOK, application.cityName(),
                                    "Заявление: " + statusLabel(application.status()),
                                    "Подано: " + date(application.createdAt())));
                        }
                        if (data.applications().isEmpty()) inventory.setItem(31, info(Material.PAPER,
                                "Нет заявлений", "Вы ещё не подавали заявление на гражданство"));
                        return;
                    }
                    CityService.CityView city = data.city();
                    inventory.setItem(13, info(Material.BELL, city.name(), "Город: " + city.slug(),
                            "Состояние: " + statusLabel(city.status())));
                    inventory.setItem(20, info(Material.NAME_TAG, "Моя должность", roleLabel(city.role()),
                            "Полномочия всегда проверяются сервером"));
                    inventory.setItem(22, info(Material.GOLD_BLOCK, "Городская казна",
                            formatMinor(city.treasuryMinor()), "Внутренний счёт CityCore"));
                    inventory.setItem(24, button(Material.PLAYER_HEAD, "Жители города", "route:CITY_MEMBERS",
                            "Состав города и игровые должности"));
                    if (city.role() == CityRole.MAYOR) {
                        inventory.setItem(29, button(Material.WRITABLE_BOOK, "Заявления граждан", "route:CITY_APPLICATIONS",
                                "Принять или отклонить запросы игроков"));
                    } else {
                        inventory.setItem(29, info(Material.PAPER, "Заявления граждан",
                                "Решения принимает мэр города"));
                    }
                    inventory.setItem(31, button(Material.BRICKS, "Предприятия города", "route:BUSINESS",
                            "Компании, регистрации и лицензии"));
                    inventory.setItem(33, button(Material.HOPPER, "Пополнить казну", "prompt:treasury_deposit",
                            "Перевести личные средства городу"));
                    inventory.setItem(38, plannedButton("taxes"));
                    inventory.setItem(40, plannedButton("laws"));
                    inventory.setItem(42, plannedButton("objects"));
                }));
    }

    private void renderCityDirectory(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Загрузка городов…"));
        storage.submit(cities::activeCities).whenComplete((items, error) -> sync(player, inventory, () -> {
            clearList(inventory);
            if (error != null) {
                inventory.setItem(22, errorItem(error));
                return;
            }
            if (items.isEmpty()) {
                inventory.setItem(22, info(Material.MAP, "Активных городов нет",
                        "Вы можете основать первый город в предыдущем меню"));
                return;
            }
            for (int i = 0; i < items.size() && i < LIST_SLOTS.length; i++) {
                CityService.CitySummary city = items.get(i);
                inventory.setItem(LIST_SLOTS[i], button(Material.BELL, city.name(), "city_apply:" + city.slug(),
                        "Жителей: " + city.citizenCount(), "Нажмите, чтобы подать заявление"));
            }
        }));
    }

    private void renderCityApplications(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Загрузка заявлений…"));
        storage.submit(() -> cities.pending(player.getUniqueId())).whenComplete((items, error) -> sync(player, inventory, () -> {
            clearList(inventory);
            if (error != null) {
                inventory.setItem(22, errorItem(error));
                return;
            }
            if (items.isEmpty()) {
                inventory.setItem(22, info(Material.PAPER, "Очередь пуста",
                        "Новых заявлений на гражданство нет"));
                return;
            }
            for (int i = 0; i < items.size() && i < LIST_SLOTS.length; i++) {
                CityService.Application application = items.get(i);
                inventory.setItem(LIST_SLOTS[i], playerHead(application.playerId(), application.playerName(),
                        "city_application:" + application.playerId(), "Заявление на гражданство",
                        "Подано: " + date(application.createdAt()), "Открыть решение"));
            }
        }));
    }

    private void renderCityApplication(Player player, Inventory inventory) {
        CityService.Application application = selectedApplication.get(player.getUniqueId());
        if (application == null) {
            inventory.setItem(22, info(Material.BARRIER, "Заявление не выбрано",
                    "Вернитесь к очереди и выберите игрока"));
            return;
        }
        inventory.setItem(13, playerHead(application.playerId(), application.playerName(), null,
                "Кандидат на гражданство", "Подано: " + date(application.createdAt())));
        inventory.setItem(29, button(Material.LIME_CONCRETE, "Принять заявление",
                "city_accept:" + application.playerId(), "Добавить игрока в город как жителя"));
        inventory.setItem(33, button(Material.RED_CONCRETE, "Отклонить заявление",
                "city_reject:" + application.playerId(), "Закрыть заявление без гражданства"));
        inventory.setItem(31, info(Material.WRITABLE_BOOK, "Решение будет записано",
                "CityCore сохранит автора и результат в аудите"));
    }

    private void renderCityMembers(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Загрузка жителей…"));
        storage.submit(() -> new MemberScreenData(cities.view(player.getUniqueId()), cities.members(player.getUniqueId())))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    clearList(inventory);
                    if (error != null) {
                        inventory.setItem(22, errorItem(error));
                        return;
                    }
                    boolean mayor = data.city() != null && data.city().role() == CityRole.MAYOR;
                    for (int i = 0; i < data.members().size() && i < LIST_SLOTS.length; i++) {
                        CityService.Member member = data.members().get(i);
                        String action = null;
                        String hint = "Просмотр участника города";
                        if (mayor && member.role() != CityRole.MAYOR) {
                            CityRole next = member.role() == CityRole.OFFICIAL ? CityRole.CITIZEN : CityRole.OFFICIAL;
                            action = "city_role:" + member.playerId() + ":" + next.name();
                            hint = next == CityRole.OFFICIAL ? "Назначить городским чиновником" : "Снять должность чиновника";
                        }
                        inventory.setItem(LIST_SLOTS[i], playerHead(member.playerId(), member.playerName(), action,
                                "Должность: " + roleLabel(member.role()), "В городе с " + date(member.joinedAt()), hint));
                    }
                }));
    }

    private void renderBusinesses(Player player, Inventory inventory, boolean pendingOnly) {
        inventory.setItem(22, loading(pendingOnly ? "Загрузка регистраций…" : "Загрузка предприятий…"));
        storage.submit(() -> new BusinessScreenData(cities.view(player.getUniqueId()),
                        businesses.list(player.getUniqueId(), pendingOnly)))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    clearList(inventory);
                    if (error != null) {
                        inventory.setItem(22, errorItem(error));
                        return;
                    }
                    if (data.city() == null) {
                        inventory.setItem(22, info(Material.BARRIER, "Требуется гражданство",
                                "Предприятия регистрируются в выбранном городе"));
                        return;
                    }
                    int limit = pendingOnly ? LIST_SLOTS.length : 21;
                    for (int i = 0; i < data.items().size() && i < limit; i++) {
                        BusinessService.BusinessView business = data.items().get(i);
                        String ownership = business.ownerId().equals(player.getUniqueId()) ? "Владелец: вы" : "Предприятие вашего города";
                        inventory.setItem(LIST_SLOTS[i], button(statusMaterial(business.status()), business.name(),
                                "business_view:" + business.id(), ownership,
                                "Состояние: " + statusLabel(business.status()), "Открыть карточку предприятия"));
                    }
                    if (data.items().isEmpty()) inventory.setItem(22, info(Material.BRICKS,
                            pendingOnly ? "Очередь пуста" : "Предприятий пока нет",
                            pendingOnly ? "Новых заявок на регистрацию нет" : "Создайте первую компанию города"));
                    if (!pendingOnly) {
                        inventory.setItem(38, button(Material.CRAFTING_TABLE, "Создать предприятие",
                                "prompt:business_register", "Подать заявку на регистрацию компании"));
                        boolean official = data.city().role() == CityRole.MAYOR || data.city().role() == CityRole.OFFICIAL;
                        if (official) inventory.setItem(40, button(Material.WRITABLE_BOOK, "Очередь регистраций",
                                "route:BUSINESS_PENDING", "Рассмотреть новые предприятия города"));
                        inventory.setItem(42, button(Material.ENCHANTED_BOOK, "Реестр лицензий",
                                "route:LICENSES", "Разрешения предприятий вашего города"));
                    }
                }));
    }

    private void renderBusinessDetail(Player player, Inventory inventory) {
        String businessId = selectedBusiness.get(player.getUniqueId());
        if (businessId == null) {
            inventory.setItem(22, info(Material.BARRIER, "Предприятие не выбрано",
                    "Вернитесь к списку и откройте карточку"));
            return;
        }
        inventory.setItem(22, loading("Загрузка карточки…"));
        storage.submit(() -> businesses.detail(player.getUniqueId(), businessId))
                .whenComplete((business, error) -> sync(player, inventory, () -> {
                    if (error != null) {
                        inventory.setItem(22, errorItem(error));
                        return;
                    }
                    inventory.setItem(13, info(statusMaterial(business.status()), business.name(),
                            "Состояние: " + statusLabel(business.status()), "Городской ID: " + business.slug()));
                    inventory.setItem(20, playerHead(business.ownerId(), business.ownerName(), null,
                            "Владелец предприятия"));
                    inventory.setItem(22, info(Material.COMPARATOR, "Состояние предприятия",
                            statusLabel(business.status()), business.accountId() == null ? "Счёт ещё не открыт" : "Внутренний счёт открыт"));
                    if (business.balanceMinor() != null) inventory.setItem(24, info(Material.GOLD_INGOT,
                            "Счёт предприятия", formatMinor(business.balanceMinor()), "Доступен владельцу и городской власти"));

                    if (business.owner() && business.accountId() != null) {
                        inventory.setItem(29, button(Material.HOPPER, "Пополнить предприятие",
                                "business_deposit:" + business.id(), "Перевести деньги из личного кошелька"));
                    }
                    if (business.official() && "PENDING".equals(business.status())) {
                        inventory.setItem(29, button(Material.LIME_CONCRETE, "Одобрить регистрацию",
                                "business_approve:" + business.id(), "Активировать предприятие и открыть счёт"));
                        inventory.setItem(33, button(Material.RED_CONCRETE, "Отклонить регистрацию",
                                "business_reject:" + business.id(), "Закрыть заявление без активации"));
                    } else if (business.official() && "ACTIVE".equals(business.status())) {
                        inventory.setItem(33, button(Material.ENCHANTED_BOOK, "Выдать лицензию",
                                "license_issue:" + business.id(), "Указать тип и срок разрешения"));
                    }

                    int index = 0;
                    for (BusinessService.LicenseView license : business.licenses()) {
                        if (index >= 7) break;
                        String action = business.official() && "ACTIVE".equals(license.status())
                                ? "license_revoke:" + business.id() + ":" + license.type() : null;
                        ItemStack item = action == null
                                ? info(Material.PAPER, license.type(), "Состояние: " + statusLabel(license.status()),
                                "До: " + date(license.expiresAt()))
                                : button(Material.ENCHANTED_BOOK, license.type(), action,
                                "Состояние: " + statusLabel(license.status()), "До: " + date(license.expiresAt()),
                                "Нажмите для отзыва лицензии");
                        inventory.setItem(37 + index++, item);
                    }
                    if (business.licenses().isEmpty()) inventory.setItem(40, info(Material.BOOK,
                            "Лицензий пока нет", "Разрешения появятся здесь после выдачи"));
                }));
    }

    private void renderLicenses(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Загрузка лицензий…"));
        storage.submit(() -> businesses.licenseRegistry(player.getUniqueId()))
                .whenComplete((items, error) -> sync(player, inventory, () -> {
                    clearList(inventory);
                    if (error != null) {
                        inventory.setItem(22, errorItem(error));
                        return;
                    }
                    if (items.isEmpty()) {
                        inventory.setItem(22, info(Material.BOOK, "Лицензий пока нет",
                                "Городская власть выдаёт их активным предприятиям"));
                        return;
                    }
                    for (int i = 0; i < items.size() && i < LIST_SLOTS.length; i++) {
                        BusinessService.LicenseCard license = items.get(i);
                        inventory.setItem(LIST_SLOTS[i], button(Material.ENCHANTED_BOOK, license.type(),
                                "business_view:" + license.businessId(), "Предприятие: " + license.businessName(),
                                "Состояние: " + statusLabel(license.status()), "До: " + date(license.expiresAt())));
                    }
                }));
    }

    private void renderEconomy(Player player, Inventory inventory) {
        inventory.setItem(13, info(Material.GOLD_INGOT, "Личный кошелёк",
                formatMinor(economy.balanceMinor(player.getUniqueId())), "Источник: EssentialsX Economy"));
        inventory.setItem(22, loading("Загрузка внутренних счетов…"));
        storage.submit(() -> new EconomyScreenData(cities.view(player.getUniqueId()),
                        businesses.list(player.getUniqueId(), false)))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    if (error != null) {
                        inventory.setItem(22, errorItem(error));
                        return;
                    }
                    if (data.city() == null) {
                        inventory.setItem(22, info(Material.MAP, "Внутренних счетов нет",
                                "Получите гражданство, чтобы работать с казной и бизнесом"));
                        return;
                    }
                    inventory.setItem(20, info(Material.GOLD_BLOCK, "Казна · " + data.city().name(),
                            formatMinor(data.city().treasuryMinor()), "Общий счёт города"));
                    inventory.setItem(24, button(Material.HOPPER, "Пополнить городскую казну",
                            "prompt:treasury_deposit", "Перевести личные средства своему городу"));
                    int index = 0;
                    for (BusinessService.BusinessView business : data.businesses()) {
                        if (!business.ownerId().equals(player.getUniqueId()) || business.accountId() == null) continue;
                        if (index >= 7) break;
                        inventory.setItem(37 + index++, button(Material.BRICKS, business.name(),
                                "business_deposit:" + business.id(), "Пополнить счёт своего предприятия",
                                "Состояние: " + statusLabel(business.status())));
                    }
                    if (index == 0) inventory.setItem(40, info(Material.PAPER, "Нет счетов предприятий",
                            "Счёт открывается после одобрения регистрации"));
                    inventory.setItem(22, info(Material.COMPARATOR, "Денежная модель",
                            "Личные деньги: EssentialsX", "Организации и города: CityCore"));
                }));
    }

    private void renderAdmin(Inventory inventory) {
        inventory.setItem(13, info(Material.COMMAND_BLOCK, "CityCore alpha.16",
                "Вертикальное ядро", "Схема базы данных: 3"));
        inventory.setItem(20, button(Material.COMPARATOR, "Проверить состояние", "command:status",
                "Версия, база данных и подключение Vault"));
        inventory.setItem(24, button(Material.REPEATER, "Перечитать настройки", "command:reload",
                "Применить безопасные параметры config.yml"));
        inventory.setItem(29, plannedButton("audit"));
        inventory.setItem(31, plannedButton("objects"));
        inventory.setItem(33, info(Material.CHEST, "Очередь восстановления",
                "Vault-переводы восстанавливаются автоматически", "Ручная панель появится вместе с аудитом"));
    }

    private void renderPlanned(Player player, Inventory inventory) {
        PlannedModule module = selectedPlanned.get(player.getUniqueId());
        if (module == null) module = PLANNED.get("roadmap");
        inventory.setItem(13, info(module.material(), module.title(), module.description(),
                "Этап: " + module.phase()));
        inventory.setItem(22, info(Material.CLOCK, "Раздел ещё не активирован",
                "Он зафиксирован в ТЗ и не потерян", "Кнопка не выполняет фиктивных операций"));
        inventory.setItem(31, info(Material.MAP, "Порядок разработки",
                "1. Вертикальное ядро", "2. Живой рынок", "3. Собственность и работа",
                "4. Межгородская система", "5. Право и преступность"));
    }

    private void renderConfirmation(Player player, Inventory inventory) {
        Confirmation confirmation = confirmations.get(player.getUniqueId());
        if (confirmation == null) {
            inventory.setItem(22, info(Material.BARRIER, "Действие устарело",
                    "Вернитесь назад и выберите его ещё раз"));
            return;
        }
        inventory.setItem(13, info(Material.WRITABLE_BOOK, confirmation.title(), confirmation.description(),
                "Перед выполнением права и состояние проверятся снова"));
        inventory.setItem(29, button(Material.LIME_CONCRETE, confirmation.confirmLabel(), "confirm:run",
                "Подтвердить действие"));
        inventory.setItem(33, button(Material.RED_CONCRETE, "Отменить", "confirm:cancel",
                "Вернуться без изменений"));
    }

    private void renderNavigation(Player player, Route route, Inventory inventory) {
        if (route != Route.HOME) inventory.setItem(BACK_SLOT, button(Material.ARROW, "Назад", "back",
                "Вернуться к предыдущему разделу"));
        if (route == Route.HOME) inventory.setItem(HOME_SLOT, info(Material.NETHER_STAR, "CityCore",
                "Главный экран городской сети"));
        else inventory.setItem(HOME_SLOT, button(Material.COMPASS, "Главный экран", "route:HOME",
                "Открыть все разделы CityCore"));
        inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "Закрыть", "close", "Вернуться в игру"));
    }

    public String action(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    public void handle(Player player, Route current, String action) {
        if (action == null) return;
        feedback.click(player);
        if (action.equals("close")) {
            player.closeInventory();
        } else if (action.equals("back")) {
            navigate(player, backRoute(player, current));
        } else if (action.startsWith("route:")) {
            Route target;
            try {
                target = Route.valueOf(action.substring(6));
            } catch (IllegalArgumentException ignored) {
                return;
            }
            if (target != Route.HOME) rememberReturn(player, target, current);
            navigate(player, target);
        } else if (action.startsWith("planned:")) {
            PlannedModule module = PLANNED.get(action.substring(8));
            if (module == null) return;
            selectedPlanned.put(player.getUniqueId(), module);
            rememberReturn(player, Route.PLANNED, current);
            navigate(player, Route.PLANNED);
        } else if (action.startsWith("command:")) {
            player.closeInventory();
            player.performCommand("cc " + action.substring(8));
        } else if (action.startsWith("city_apply:")) {
            String slug = action.substring("city_apply:".length());
            confirm(player, "Заявление в город «" + slug + "»",
                    "После отправки решение должен принять мэр.", "Подать заявление",
                    () -> { cities.apply(player.getUniqueId(), slug); return "Заявление отправлено мэру города."; }, Route.CITY);
        } else if (action.startsWith("city_application:")) {
            UUID applicant = uuid(action.substring("city_application:".length()));
            if (applicant == null) return;
            rememberReturn(player, Route.CITY_APPLICATION_DETAIL, current);
            storage.submit(() -> cities.pending(player.getUniqueId())).whenComplete((items, error) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (error != null) {
                            failure(player, error);
                            return;
                        }
                        items.stream().filter(item -> item.playerId().equals(applicant)).findFirst().ifPresent(item -> {
                            selectedApplication.put(player.getUniqueId(), item);
                            navigate(player, Route.CITY_APPLICATION_DETAIL);
                        });
                    }));
        } else if (action.startsWith("city_accept:") || action.startsWith("city_reject:")) {
            boolean accept = action.startsWith("city_accept:");
            UUID applicant = uuid(action.substring(action.indexOf(':') + 1));
            if (applicant == null) return;
            confirm(player, accept ? "Принять нового жителя" : "Отклонить заявление",
                    accept ? "Игрок получит гражданство и роль жителя." : "Заявление будет закрыто без гражданства.",
                    accept ? "Принять" : "Отклонить",
                    () -> { cities.decide(player.getUniqueId(), applicant, accept); return accept ? "Игрок принят в город." : "Заявление отклонено."; },
                    Route.CITY_APPLICATIONS);
        } else if (action.startsWith("city_role:")) {
            String[] parts = action.split(":", 3);
            UUID member = parts.length == 3 ? uuid(parts[1]) : null;
            if (member == null) return;
            CityRole role;
            try { role = CityRole.valueOf(parts[2]); } catch (IllegalArgumentException invalid) { return; }
            confirm(player, "Изменить городскую должность", "Новая должность: " + roleLabel(role) + ".",
                    "Изменить должность", () -> {
                        cities.setRole(player.getUniqueId(), member, role);
                        return "Должность участника изменена.";
                    }, Route.CITY_MEMBERS);
        } else if (action.startsWith("business_view:")) {
            selectedBusiness.put(player.getUniqueId(), action.substring("business_view:".length()));
            rememberReturn(player, Route.BUSINESS_DETAIL, current);
            navigate(player, Route.BUSINESS_DETAIL);
        } else if (action.startsWith("business_approve:") || action.startsWith("business_reject:")) {
            boolean approve = action.startsWith("business_approve:");
            String id = action.substring(action.indexOf(':') + 1);
            confirm(player, approve ? "Одобрить предприятие" : "Отклонить предприятие",
                    approve ? "Компания станет активной и получит внутренний счёт." : "Регистрация будет закрыта без открытия счёта.",
                    approve ? "Одобрить" : "Отклонить",
                    () -> { businesses.decide(player.getUniqueId(), id, approve); return approve ? "Предприятие одобрено." : "Предприятие отклонено."; },
                    Route.BUSINESS_PENDING);
        } else if (action.startsWith("license_issue:")) {
            String id = action.substring("license_issue:".length());
            prompts.begin(player, "Введите тип лицензии (например, TRADE):", type ->
                    prompts.begin(player, "Введите срок лицензии в днях:", days -> {
                        try {
                            int value = Integer.parseInt(days);
                            execute(player, () -> "Лицензия выдана: " + businesses.issueLicense(player.getUniqueId(), id, type, value).type() + ".",
                                    Route.BUSINESS_DETAIL);
                        } catch (NumberFormatException invalid) {
                            UiText.error(player, "Срок необходимо указать целым числом дней.");
                            feedback.failure(player);
                        }
                    }));
        } else if (action.startsWith("license_revoke:")) {
            String[] parts = action.split(":", 3);
            if (parts.length != 3) return;
            confirm(player, "Отозвать лицензию " + parts[2], "Новые операции этого типа будут запрещены.",
                    "Отозвать лицензию", () -> {
                        businesses.revokeLicense(player.getUniqueId(), parts[1], parts[2]);
                        return "Лицензия отозвана.";
                    }, Route.BUSINESS_DETAIL);
        } else if (action.startsWith("business_deposit:")) {
            String id = action.substring("business_deposit:".length());
            prompts.begin(player, "Введите сумму пополнения предприятия:", value -> {
                try {
                    long amount = MinorUnits.parse(value, plugin.config().currencyScale());
                    confirmMain(player, "Пополнить предприятие", "Будет списано: " + formatMinor(amount),
                            "Подтвердить перевод", () -> vaultTransfers.depositToBusiness(player, id, amount),
                            Route.BUSINESS_DETAIL);
                } catch (RuntimeException error) {
                    failure(player, error);
                }
            });
        } else if (action.equals("prompt:city_create")) {
            prompts.begin(player, "Придумайте системный ID города (a-z, 0-9, _ или -):", slug ->
                    prompts.begin(player, "Введите отображаемое название города:", name -> execute(player,
                            () -> { cities.create(player.getUniqueId(), slug, name); return "Город «" + name + "» создан."; }, Route.CITY)));
        } else if (action.equals("prompt:business_register")) {
            prompts.begin(player, "Придумайте системный ID предприятия:", slug ->
                    prompts.begin(player, "Введите название предприятия:", name -> execute(player,
                            () -> { businesses.register(player.getUniqueId(), slug, name); return "Заявка на регистрацию отправлена."; }, Route.BUSINESS)));
        } else if (action.equals("prompt:treasury_deposit")) {
            prompts.begin(player, "Введите сумму пополнения городской казны:", value -> {
                try {
                    long amount = MinorUnits.parse(value, plugin.config().currencyScale());
                    confirmMain(player, "Пополнить городскую казну", "Будет списано: " + formatMinor(amount),
                            "Подтвердить перевод", () -> vaultTransfers.depositToTreasury(player, amount), current);
                } catch (RuntimeException error) {
                    failure(player, error);
                }
            });
        } else if (action.equals("confirm:run")) {
            Confirmation confirmation = confirmations.remove(player.getUniqueId());
            if (confirmation == null) return;
            if (confirmation.mainOperation() != null) {
                player.closeInventory();
                UiText.info(player, "Перевод отправлен на обработку…");
                try { confirmation.mainOperation().run(); }
                catch (RuntimeException error) { failure(player, error); }
            } else execute(player, confirmation.operation(), confirmation.returnRoute());
        } else if (action.equals("confirm:cancel")) {
            Confirmation confirmation = confirmations.remove(player.getUniqueId());
            navigate(player, confirmation == null ? Route.HOME : confirmation.returnRoute());
        }
    }

    public void clear(UUID playerId) {
        selectedBusiness.remove(playerId);
        selectedApplication.remove(playerId);
        selectedPlanned.remove(playerId);
        confirmations.remove(playerId);
        returnRoutes.remove(playerId);
    }

    private void confirm(Player player, String title, String description, String confirmLabel,
                         Callable<String> operation, Route returnRoute) {
        confirmations.put(player.getUniqueId(), new Confirmation(title, description, confirmLabel, operation, null, returnRoute));
        navigate(player, Route.CONFIRM);
    }

    private void confirmMain(Player player, String title, String description, String confirmLabel,
                             Runnable operation, Route returnRoute) {
        confirmations.put(player.getUniqueId(), new Confirmation(title, description, confirmLabel, null, operation, returnRoute));
        navigate(player, Route.CONFIRM);
    }

    private void execute(Player player, Callable<String> operation, Route returnRoute) {
        storage.submit(() -> {
            try { return operation.call(); }
            catch (Exception error) { throw new RuntimeException(error); }
        }).whenComplete((message, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (error != null) failure(player, error);
            else {
                feedback.success(player);
                UiText.success(player, message);
                navigate(player, returnRoute);
            }
        }));
    }

    private Route backRoute(Player player, Route route) {
        Map<Route, Route> remembered = returnRoutes.get(player.getUniqueId());
        if (remembered != null) {
            Route target = remembered.remove(route);
            if (target != null) return target;
        }
        return switch (route) {
            case HOME -> Route.HOME;
            case CITY_DIRECTORY, CITY_APPLICATIONS, CITY_MEMBERS -> Route.CITY;
            case CITY_APPLICATION_DETAIL -> Route.CITY_APPLICATIONS;
            case BUSINESS_DETAIL, BUSINESS_PENDING, LICENSES -> Route.BUSINESS;
            case CONFIRM -> {
                Confirmation confirmation = confirmations.remove(player.getUniqueId());
                yield confirmation == null ? Route.HOME : confirmation.returnRoute();
            }
            default -> Route.HOME;
        };
    }

    private void rememberReturn(Player player, Route target, Route current) {
        returnRoutes.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>()).put(target, current);
    }

    private ItemStack plannedButton(String key) {
        PlannedModule module = PLANNED.get(key);
        return button(module.material(), module.title(), "planned:" + key,
                module.description(), "Этап: " + module.phase());
    }

    private ItemStack button(Material material, String name, String action, String... lore) {
        ItemStack item = info(material, name, lore);
        item.editMeta(meta -> {
            List<Component> lines = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            lines.add(Component.empty());
            lines.add(UiText.hint("ЛКМ  ›  выбрать"));
            meta.lore(lines);
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, action);
        });
        return item;
    }

    private ItemStack info(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            meta.displayName(UiText.name(name));
            meta.lore(Arrays.stream(lore).map(UiText::lore).toList());
        });
        return item;
    }

    private ItemStack playerHead(UUID playerId, String name, String action, String... lore) {
        ItemStack item = action == null ? info(Material.PLAYER_HEAD, name, lore)
                : button(Material.PLAYER_HEAD, name, action, lore);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        meta.setOwningPlayer(Bukkit.getOfflinePlayer(playerId));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack loading(String text) {
        return info(Material.CLOCK, text, "Данные читаются без блокировки игрового потока");
    }

    private ItemStack errorItem(Throwable error) {
        return info(Material.BARRIER, "Не удалось загрузить раздел", rootMessage(error),
                "Повторите попытку или передайте сообщение администратору");
    }

    private void decorate(Inventory inventory) {
        ItemStack dark = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        dark.editMeta(meta -> meta.displayName(Component.empty()));
        ItemStack gray = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        gray.editMeta(meta -> meta.displayName(Component.empty()));
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9, column = slot % 9;
            if (row == 0 || row == 5 || column == 0 || column == 8) {
                inventory.setItem(slot, (slot % 2 == 0) ? dark : gray);
            }
        }
    }

    private void clearList(Inventory inventory) {
        for (int slot : LIST_SLOTS) inventory.setItem(slot, null);
    }

    private void sync(Player player, Inventory inventory, Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.getOpenInventory().getTopInventory() == inventory) action.run();
        });
    }

    private void failure(Player player, Throwable error) {
        feedback.failure(player);
        UiText.error(player, "Не удалось выполнить действие: " + rootMessage(error));
    }

    private UUID uuid(String value) {
        try { return UUID.fromString(value); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private String formatMinor(long amount) {
        return MinorUnits.format(amount, plugin.config().currencyScale()) + " мон.";
    }

    private String date(Instant instant) {
        return instant == null ? "без срока" : DATE.format(instant);
    }

    private Material statusMaterial(String status) {
        return switch (status) {
            case "ACTIVE" -> Material.LIME_CONCRETE;
            case "PENDING", "WARNING", "EXPIRING" -> Material.YELLOW_CONCRETE;
            case "REJECTED", "REVOKED", "BLOCKED", "CLOSED" -> Material.RED_CONCRETE;
            case "DEBT", "SUSPENDED", "CLOSING", "EXPIRED" -> Material.ORANGE_CONCRETE;
            default -> Material.BRICKS;
        };
    }

    private String roleLabel(CityRole role) {
        return switch (role) {
            case CITIZEN -> "житель";
            case OFFICIAL -> "городской чиновник";
            case MAYOR -> "мэр";
        };
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "ACTIVE" -> "активно";
            case "PENDING" -> "ожидает решения";
            case "ACCEPTED" -> "принято";
            case "REJECTED" -> "отклонено";
            case "WARNING" -> "требует внимания";
            case "DEBT" -> "есть задолженность";
            case "SUSPENDED" -> "приостановлено";
            case "BLOCKED" -> "заблокировано";
            case "CLOSING" -> "закрывается";
            case "CLOSED" -> "закрыто";
            case "EXPIRING" -> "скоро истекает";
            case "EXPIRED" -> "истекло";
            case "REVOKED" -> "отозвано";
            default -> status.toLowerCase(Locale.ROOT);
        };
    }

    private String title(Player player, Route route) {
        return switch (route) {
            case HOME -> "CityCore · Телефон";
            case PROFILE -> "CityCore · Профиль";
            case CITY -> "CityCore · Город";
            case CITY_DIRECTORY -> "CityCore · Каталог городов";
            case CITY_APPLICATIONS -> "CityCore · Заявления";
            case CITY_APPLICATION_DETAIL -> "CityCore · Решение по заявлению";
            case CITY_MEMBERS -> "CityCore · Жители";
            case BUSINESS -> "CityCore · Предприятия";
            case BUSINESS_PENDING -> "CityCore · Регистрации";
            case BUSINESS_DETAIL -> "CityCore · Карточка предприятия";
            case LICENSES -> "CityCore · Лицензии";
            case ECONOMY -> "CityCore · Финансы";
            case ADMIN -> "CityCore · Управление";
            case PLANNED -> "CityCore · " + selectedPlanned.getOrDefault(player.getUniqueId(), PLANNED.get("roadmap")).title();
            case CONFIRM -> "CityCore · Подтверждение";
        };
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record PlannedModule(Material material, String title, String phase, String description) {}
    private record Confirmation(String title, String description, String confirmLabel,
                                Callable<String> operation, Runnable mainOperation, Route returnRoute) {}
    private record ProfileData(CityService.CityView city, List<BusinessService.BusinessView> businesses) {}
    private record CityScreenData(CityService.CityView city, List<CityService.PlayerApplication> applications) {}
    private record MemberScreenData(CityService.CityView city, List<CityService.Member> members) {}
    private record BusinessScreenData(CityService.CityView city, List<BusinessService.BusinessView> items) {}
    private record EconomyScreenData(CityService.CityView city, List<BusinessService.BusinessView> businesses) {}
}
