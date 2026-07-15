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
import ru.citycore.economy.EmissionService;
import ru.citycore.economy.MinorUnits;
import ru.citycore.economy.VaultTransferCoordinator;
import ru.citycore.permission.RoleMirror;
import ru.citycore.permission.Capability;
import ru.citycore.permission.CapabilityService;

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
    private static final int[] LIST_SLOTS = GuiLayout.contentSlots();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            .withZone(ZoneId.systemDefault());
    private final CityCorePlugin plugin;
    private final EconomyGateway economy;
    private final StorageExecutor storage;
    private final CityService cities;
    private final BusinessService businesses;
    private final ChatPromptService prompts;
    private final GuiFeedback feedback;
    private final VaultTransferCoordinator vaultTransfers;
    private final EmissionService emission;
    private final RoleMirror roleMirror;
    private final CapabilityService capabilities = new CapabilityService();
    private final NamespacedKey actionKey;
    private final Map<UUID, String> selectedBusiness = new ConcurrentHashMap<>();
    private final Map<UUID, CityService.Application> selectedApplication = new ConcurrentHashMap<>();
    private final Map<UUID, CityService.FoundationApplication> selectedFoundation = new ConcurrentHashMap<>();
    private final Map<UUID, Confirmation> confirmations = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Route, Route>> returnRoutes = new ConcurrentHashMap<>();

    public GuiService(CityCorePlugin plugin, EconomyGateway economy, StorageExecutor storage, CityService cities,
                      BusinessService businesses, ChatPromptService prompts, GuiFeedback feedback,
                      VaultTransferCoordinator vaultTransfers, EmissionService emission, RoleMirror roleMirror) {
        this.plugin = plugin;
        this.economy = economy;
        this.storage = storage;
        this.cities = cities;
        this.businesses = businesses;
        this.prompts = prompts;
        this.feedback = feedback;
        this.vaultTransfers = vaultTransfers;
        this.emission = emission;
        this.roleMirror = roleMirror;
        this.actionKey = new NamespacedKey(plugin, "gui_action");
    }

    public void open(Player player, Route route) {
        open(player, route, true);
    }

    private void navigate(Player player, Route route) {
        open(player, route, false);
    }

    private void open(Player player, Route route, boolean openingSound) {
        if (route == Route.ADMIN && !capabilities.allows(player, null, Capability.ADMIN_WORKSPACE)) {
            UiText.error(player, "У вас нет доступа к управлению системой.");
            route = Route.HOME;
        }
        if ((route == Route.CITY_FOUNDATIONS_ADMIN || route == Route.CITY_FOUNDATION_DETAIL)
                && !capabilities.allows(player, null, Capability.REVIEW_CITY_FOUNDATIONS)) {
            UiText.error(player, "У вас нет доступа к основанию городов.");
            route = Route.HOME;
        }
        if (route == Route.EMISSION && (!capabilities.allows(player, null, Capability.ISSUE_CURRENCY)
                || !plugin.config().emissionEnabled())) {
            UiText.error(player, "Эмиссия недоступна.");
            route = Route.HOME;
        }
        CityCoreHolder holder = new CityCoreHolder(route);
        Inventory inventory = plugin.getServer().createInventory(holder, GuiLayout.SIZE,
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
            case HELP -> renderHelp(inventory);
            case MAYOR -> renderMayor(player, inventory);
            case GOVERNMENT -> renderGovernment(player, inventory);
            case ADMIN -> renderAdmin(player, inventory);
            case CITY_FOUNDATIONS_ADMIN -> renderFoundationApplications(player, inventory);
            case CITY_FOUNDATION_DETAIL -> renderFoundationDetail(player, inventory);
            case EMISSION -> renderEmission(player, inventory);
            case CONFIRM -> renderConfirmation(player, inventory);
        }
        renderNavigation(player, route, inventory);
    }

    private void renderHome(Player player, Inventory inventory) {
        inventory.setItem(10, lane(Material.PLAYER_HEAD, "Личное"));
        inventory.setItem(11, button(Material.PLAYER_HEAD, "Профиль", "route:PROFILE",
                "Гражданство, должность и личные связи"));
        inventory.setItem(12, button(Material.GOLD_INGOT, "Финансы", "route:ECONOMY",
                "Кошелёк, казна и доступные счета"));
        inventory.setItem(13, loading("Проверяем предприятия…"));

        inventory.setItem(19, lane(Material.BELL, "Город"));
        inventory.setItem(20, button(Material.COMPASS, "Каталог городов", "route:CITY_DIRECTORY",
                "Города сервера и заявления на гражданство"));
        inventory.setItem(21, button(Material.BELL, "Мой город", "route:CITY",
                "Гражданство, казна и городские действия"));
        inventory.setItem(22, loading("Проверяем основание города…"));
        inventory.setItem(23, loading("Проверяем состав города…"));

        inventory.setItem(28, lane(Material.GOLD_INGOT, "Экономика"));
        inventory.setItem(29, button(Material.BRICKS, "Предприятия", "route:BUSINESS",
                "Компании, регистрации и рабочие счета"));
        inventory.setItem(30, button(Material.GOLD_BLOCK, "Счета и казна", "route:ECONOMY",
                "Доступные вам денежные контуры"));

        inventory.setItem(37, lane(Material.COMPASS, "Сервисы"));
        inventory.setItem(38, button(Material.KNOWLEDGE_BOOK, "Справка по панели", "route:HELP",
                "Где находятся городские и служебные действия"));

        storage.submit(() -> new HomeContext(cities.view(player.getUniqueId()),
                        cities.latestFoundation(player.getUniqueId()), businesses.list(player.getUniqueId(), false)))
                .whenComplete((context, error) -> sync(player, inventory, () -> {
                    if (error != null) {
                        inventory.setItem(13, errorItem(error));
                        inventory.setItem(22, errorItem(error));
                        inventory.setItem(23, errorItem(error));
                        return;
                    }
                    long owned = context.businesses().stream()
                            .filter(item -> item.ownerId().equals(player.getUniqueId())).count();
                    if (owned > 0) {
                        inventory.setItem(13, button(Material.CRAFTING_TABLE, "Мои предприятия · " + owned,
                                "route:BUSINESS", "Компании, которыми вы владеете"));
                    } else {
                        inventory.setItem(13, null);
                    }
                    renderFoundationShortcutAt(inventory, 22, context.city(), context.foundation());
                    if (context.city() == null) {
                        inventory.setItem(23, null);
                    } else {
                        inventory.setItem(23, button(Material.PLAYER_HEAD, "Жители · " + context.city().name(),
                                "route:CITY_MEMBERS", "Состав и должности вашего города",
                                "Ваша роль: " + roleLabel(context.city().role())));
                    }
                }));
    }

    private void renderProfile(Player player, Inventory inventory) {
        inventory.setItem(10, playerHead(player.getUniqueId(), player.getName(), null,
                "Игровой профиль CityCore", "UUID хранится как основной идентификатор"));
        inventory.setItem(19, info(Material.GOLD_INGOT, "Личный кошелёк",
                formatMinor(economy.balanceMinor(player.getUniqueId())), "Источник: EssentialsX Economy"));
        inventory.setItem(11, loading("Загрузка связей профиля…"));
        storage.submit(() -> new ProfileData(cities.view(player.getUniqueId()), businesses.list(player.getUniqueId(), false)))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    if (error != null) {
                        inventory.setItem(11, errorItem(error));
                        return;
                    }
                    if (data.city() == null) {
                        inventory.setItem(12, info(Material.MAP, "Без гражданства",
                                "Вы пока не состоите в городе", "Открыть варианты можно в разделе «Город»"));
                    } else {
                        inventory.setItem(12, button(Material.BELL, data.city().name(), "route:CITY",
                                "Должность: " + roleLabel(data.city().role()), "Город: " + data.city().slug()));
                    }
                    long owned = data.businesses().stream().filter(item -> item.ownerId().equals(player.getUniqueId())).count();
                    if (owned > 0) inventory.setItem(13, button(Material.BRICKS, "Мои предприятия · " + owned,
                            "route:BUSINESS", "Открыть деловой раздел"));
                    inventory.setItem(11, info(Material.CLOCK, "Профиль активен",
                            "Все связи сохранены по UUID", "Последний вход: сейчас"));
                }));
    }

    private void renderCity(Player player, Inventory inventory) {
        inventory.setItem(10, loading("Загрузка городского профиля…"));
        storage.submit(() -> new CityScreenData(cities.view(player.getUniqueId()),
                        cities.applications(player.getUniqueId()), cities.latestFoundation(player.getUniqueId())))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    if (error != null) {
                        inventory.setItem(10, errorItem(error));
                        return;
                    }
                    if (data.city() == null) {
                        inventory.setItem(10, info(Material.MAP, "Город не выбран",
                                "Гражданство оформляется через заявление", "Одновременно доступен один город"));
                        inventory.setItem(11, button(Material.COMPASS, "Каталог городов", "route:CITY_DIRECTORY",
                                "Выбрать активный город и подать заявление"));
                        renderFoundationShortcutAt(inventory, 12, null, data.foundation());
                        int index = 0;
                        for (CityService.PlayerApplication application : data.applications()) {
                            if (index >= 5) break;
                            inventory.setItem(19 + index++, info(Material.WRITABLE_BOOK, application.cityName(),
                                    "Заявление: " + statusLabel(application.status()),
                                    "Подано: " + date(application.createdAt())));
                        }
                        if (data.applications().isEmpty()) inventory.setItem(19, info(Material.PAPER,
                                "Нет заявлений", "Вы ещё не подавали заявление на гражданство"));
                        return;
                    }
                    CityService.CityView city = data.city();
                    inventory.setItem(10, info(Material.BELL, city.name(), "Город: " + city.slug(),
                            "Состояние: " + statusLabel(city.status())));
                    inventory.setItem(11, info(Material.NAME_TAG, "Моя должность", roleLabel(city.role()),
                            "Полномочия всегда проверяются сервером"));
                    inventory.setItem(12, info(Material.GOLD_BLOCK, "Городская казна",
                            formatMinor(city.treasuryMinor()), "Внутренний счёт CityCore"));
                    inventory.setItem(19, button(Material.PLAYER_HEAD, "Жители города", "route:CITY_MEMBERS",
                            "Состав города и игровые должности"));
                    if (city.role() == CityRole.MAYOR) {
                        inventory.setItem(20, button(Material.WRITABLE_BOOK, "Заявления граждан", "route:CITY_APPLICATIONS",
                                "Принять или отклонить запросы игроков"));
                    }
                    inventory.setItem(city.role() == CityRole.MAYOR ? 21 : 20,
                            button(Material.BRICKS, "Предприятия города", "route:BUSINESS",
                            "Компании, регистрации и лицензии"));
                    inventory.setItem(city.role() == CityRole.MAYOR ? 22 : 21,
                            button(Material.HOPPER, "Пополнить казну", "prompt:treasury_deposit",
                            "Перевести личные средства городу"));
                    inventory.setItem(city.role() == CityRole.MAYOR ? 23 : 22,
                            disabled(Material.FILLED_MAP, "Основать другой город",
                            "Недоступно: сначала необходимо покинуть текущее гражданство"));
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
            inventory.setItem(10, info(Material.BARRIER, "Заявление не выбрано",
                    "Вернитесь к очереди и выберите игрока"));
            return;
        }
        inventory.setItem(10, playerHead(application.playerId(), application.playerName(), null,
                "Кандидат на гражданство", "Подано: " + date(application.createdAt())));
        inventory.setItem(19, button(Material.LIME_CONCRETE, "Принять заявление",
                "city_accept:" + application.playerId(), "Добавить игрока в город как жителя"));
        inventory.setItem(20, button(Material.RED_CONCRETE, "Отклонить заявление",
                "city_reject:" + application.playerId(), "Закрыть заявление без гражданства"));
        inventory.setItem(11, info(Material.WRITABLE_BOOK, "Решение будет записано",
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
                        inventory.setItem(37, button(Material.CRAFTING_TABLE, "Создать предприятие",
                                "prompt:business_register", "Подать заявку на регистрацию компании"));
                        boolean official = data.city().role() == CityRole.MAYOR || data.city().role() == CityRole.OFFICIAL;
                        if (official) {
                            inventory.setItem(38, button(Material.WRITABLE_BOOK, "Очередь регистраций",
                                    "route:BUSINESS_PENDING", "Рассмотреть новые предприятия города"));
                            inventory.setItem(39, button(Material.ENCHANTED_BOOK, "Реестр лицензий",
                                    "route:LICENSES", "Разрешения предприятий вашего города"));
                        }
                    }
                }));
    }

    private void renderBusinessDetail(Player player, Inventory inventory) {
        String businessId = selectedBusiness.get(player.getUniqueId());
        if (businessId == null) {
            inventory.setItem(10, info(Material.BARRIER, "Предприятие не выбрано",
                    "Вернитесь к списку и откройте карточку"));
            return;
        }
        inventory.setItem(10, loading("Загрузка карточки…"));
        storage.submit(() -> businesses.detail(player.getUniqueId(), businessId))
                .whenComplete((business, error) -> sync(player, inventory, () -> {
                    if (error != null) {
                        inventory.setItem(10, errorItem(error));
                        return;
                    }
                    inventory.setItem(10, info(statusMaterial(business.status()), business.name(),
                            "Состояние: " + statusLabel(business.status()), "Городской ID: " + business.slug()));
                    inventory.setItem(11, playerHead(business.ownerId(), business.ownerName(), null,
                            "Владелец предприятия"));
                    inventory.setItem(12, info(Material.COMPARATOR, "Состояние предприятия",
                            statusLabel(business.status()), business.accountId() == null ? "Счёт ещё не открыт" : "Внутренний счёт открыт"));
                    if (business.balanceMinor() != null) inventory.setItem(13, info(Material.GOLD_INGOT,
                            "Счёт предприятия", formatMinor(business.balanceMinor()), "Доступен владельцу и городской власти"));

                    int actionSlot = 19;
                    if (business.owner() && business.accountId() != null) {
                        inventory.setItem(actionSlot++, button(Material.HOPPER, "Пополнить предприятие",
                                "business_deposit:" + business.id(), "Перевести деньги из личного кошелька"));
                    }
                    if (business.official() && "PENDING".equals(business.status())) {
                        inventory.setItem(actionSlot++, button(Material.LIME_CONCRETE, "Одобрить регистрацию",
                                "business_approve:" + business.id(), "Активировать предприятие и открыть счёт"));
                        inventory.setItem(actionSlot, button(Material.RED_CONCRETE, "Отклонить регистрацию",
                                "business_reject:" + business.id(), "Закрыть заявление без активации"));
                    } else if (business.official() && "ACTIVE".equals(business.status())) {
                        inventory.setItem(actionSlot, button(Material.ENCHANTED_BOOK, "Выдать лицензию",
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
                        inventory.setItem(28 + index++, item);
                    }
                    if (business.licenses().isEmpty()) inventory.setItem(28, info(Material.BOOK,
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
        inventory.setItem(10, info(Material.GOLD_INGOT, "Личный кошелёк",
                formatMinor(economy.balanceMinor(player.getUniqueId())), "Источник: EssentialsX Economy"));
        inventory.setItem(11, loading("Загрузка внутренних счетов…"));
        storage.submit(() -> new EconomyScreenData(cities.view(player.getUniqueId()),
                        businesses.list(player.getUniqueId(), false)))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    if (error != null) {
                        inventory.setItem(11, errorItem(error));
                        return;
                    }
                    if (data.city() == null) {
                        inventory.setItem(11, info(Material.MAP, "Внутренних счетов нет",
                                "Получите гражданство, чтобы работать с казной и бизнесом"));
                        return;
                    }
                    inventory.setItem(12, info(Material.GOLD_BLOCK, "Казна · " + data.city().name(),
                            formatMinor(data.city().treasuryMinor()), "Общий счёт города"));
                    inventory.setItem(13, button(Material.HOPPER, "Пополнить городскую казну",
                            "prompt:treasury_deposit", "Перевести личные средства своему городу"));
                    int index = 0;
                    for (BusinessService.BusinessView business : data.businesses()) {
                        if (!business.ownerId().equals(player.getUniqueId()) || business.accountId() == null) continue;
                        if (index >= 7) break;
                        inventory.setItem(19 + index++, button(Material.BRICKS, business.name(),
                                "business_deposit:" + business.id(), "Пополнить счёт своего предприятия",
                                "Состояние: " + statusLabel(business.status())));
                    }
                    if (index == 0) inventory.setItem(19, info(Material.PAPER, "Нет счетов предприятий",
                            "Счёт открывается после одобрения регистрации"));
                    inventory.setItem(11, info(Material.COMPARATOR, "Денежная модель",
                            "Личные деньги: EssentialsX", "Организации и города: CityCore"));
                }));
    }

    private void renderHelp(Inventory inventory) {
        inventory.setItem(10, info(Material.COMPASS, "Навигация",
                "Главная панель разделена на четыре строки", "Все рабочие кнопки идут слева направо"));
        inventory.setItem(11, info(Material.NAME_TAG, "Личное",
                "Профиль, личные финансы и ваши предприятия"));
        inventory.setItem(12, info(Material.BELL, "Город",
                "Каталог, гражданство, основание и жители"));
        inventory.setItem(13, info(Material.GOLD_INGOT, "Экономика",
                "Предприятия, казна и доступные счета"));
        inventory.setItem(14, info(Material.COMMAND_BLOCK, "Полномочия",
                "Доступные рабочие разделы собраны внизу", "Роль повторно проверяется перед каждым действием"));
        inventory.setItem(19, info(Material.WRITABLE_BOOK, "Заявки",
                "Решения принимаются в соответствующем рабочем разделе"));
        inventory.setItem(20, info(Material.ENCHANTED_BOOK, "Лицензии",
                "Владелец видит их в карточке предприятия", "Мэр и чиновник — в служебном реестре"));
        inventory.setItem(21, info(Material.CLOCK, "Будущие механики",
                "Не занимают места, пока не подключены к серверной логике"));
    }

    private void renderMayor(Player player, Inventory inventory) {
        inventory.setItem(10, loading("Проверяем полномочия мэра…"));
        storage.submit(() -> cities.view(player.getUniqueId())).whenComplete((city, error) ->
                sync(player, inventory, () -> {
                    if (error != null) { inventory.setItem(10, errorItem(error)); return; }
                    if (!capabilities.allows(player, city, Capability.MAYOR_WORKSPACE)) {
                        inventory.setItem(10, disabled(Material.BARRIER, "Раздел мэрии недоступен",
                                "Требуется роль мэра действующего города"));
                        return;
                    }
                    inventory.setItem(10, info(Material.GOLDEN_HELMET, "Мэрия · " + city.name(),
                            "Рабочий раздел главы города", "Казна: " + formatMinor(city.treasuryMinor())));
                    inventory.setItem(11, button(Material.WRITABLE_BOOK, "Заявления жителей", "route:CITY_APPLICATIONS",
                            "Рассмотреть запросы на гражданство"));
                    inventory.setItem(12, button(Material.PLAYER_HEAD, "Жители и должности", "route:CITY_MEMBERS",
                            "Состав города и назначение чиновников"));
                    inventory.setItem(13, button(Material.BRICKS, "Предприятия города", "route:BUSINESS",
                            "Компании и их текущее состояние"));
                    inventory.setItem(14, button(Material.WRITABLE_BOOK, "Регистрации", "route:BUSINESS_PENDING",
                            "Одобрение и отклонение новых предприятий"));
                    inventory.setItem(15, button(Material.ENCHANTED_BOOK, "Лицензии", "route:LICENSES",
                            "Реестр разрешений предприятий"));
                    inventory.setItem(16, button(Material.GOLD_BLOCK, "Казна и счета", "route:ECONOMY",
                            "Баланс города и пополнение"));
                }));
    }

    private void renderGovernment(Player player, Inventory inventory) {
        inventory.setItem(10, loading("Проверяем полномочия госструктуры…"));
        storage.submit(() -> cities.view(player.getUniqueId())).whenComplete((city, error) ->
                sync(player, inventory, () -> {
                    if (error != null) { inventory.setItem(10, errorItem(error)); return; }
                    if (!capabilities.allows(player, city, Capability.GOVERNMENT_WORKSPACE)) {
                        inventory.setItem(10, disabled(Material.BARRIER, "Служебный раздел недоступен",
                                "Требуется городская должность"));
                        return;
                    }
                    inventory.setItem(10, info(Material.SHIELD, "Управление · " + city.name(),
                            "Рабочие реестры городской службы", "Должность: " + roleLabel(city.role())));
                    inventory.setItem(11, button(Material.BRICKS, "Реестр предприятий", "route:BUSINESS",
                            "Компании вашего города"));
                    inventory.setItem(12, button(Material.WRITABLE_BOOK, "Очередь регистраций", "route:BUSINESS_PENDING",
                            "Одобрение и отклонение компаний"));
                    inventory.setItem(13, button(Material.ENCHANTED_BOOK, "Реестр лицензий", "route:LICENSES",
                            "Выданные разрешения предприятий"));
                    inventory.setItem(14, button(Material.PLAYER_HEAD, "Жители", "route:CITY_MEMBERS",
                            "Состав городского населения"));
                }));
    }

    private void renderAdmin(Player player, Inventory inventory) {
        inventory.setItem(10, info(Material.COMMAND_BLOCK, "Администрирование",
                "Административный центр", "Схема базы данных: 4"));
        int slot = 11;
        inventory.setItem(slot++, button(Material.COMPARATOR, "Состояние системы", "command:status",
                "Версия, база данных и подключение Vault"));
        inventory.setItem(slot++, button(Material.REPEATER, "Перечитать настройки", "command:reload",
                "Применить безопасные параметры config.yml"));
        if (capabilities.allows(player, null, Capability.REVIEW_CITY_FOUNDATIONS)) {
            inventory.setItem(slot++, button(Material.FILLED_MAP, "Основание городов", "route:CITY_FOUNDATIONS_ADMIN",
                    "Очередь заявок новых городов"));
        }
        if (capabilities.allows(player, null, Capability.ISSUE_CURRENCY) && plugin.config().emissionEnabled()) {
            inventory.setItem(slot, button(Material.RAW_GOLD, "Эмиссия", "route:EMISSION",
                    "Пополнение казны через ledger", "Каждая операция требует основания"));
        }
    }

    private void renderFoundationApplications(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Загрузка заявок на основание…"));
        storage.submit(cities::pendingFoundations).whenComplete((items, error) -> sync(player, inventory, () -> {
            clearList(inventory);
            if (error != null) { inventory.setItem(22, errorItem(error)); return; }
            if (items.isEmpty()) {
                inventory.setItem(22, info(Material.MAP, "Очередь пуста", "Новых заявок на основание города нет"));
                return;
            }
            for (int i = 0; i < items.size() && i < LIST_SLOTS.length; i++) {
                CityService.FoundationApplication application = items.get(i);
                inventory.setItem(LIST_SLOTS[i], playerHead(application.founderId(), application.name(),
                        "foundation_view:" + application.id(), "Основатель: " + application.founderName(),
                        "ID: " + application.slug(), "Подано: " + date(application.createdAt())));
            }
        }));
    }

    private void renderFoundationDetail(Player player, Inventory inventory) {
        CityService.FoundationApplication application = selectedFoundation.get(player.getUniqueId());
        if (application == null) {
            inventory.setItem(10, disabled(Material.BARRIER, "Заявка не выбрана",
                    "Вернитесь к очереди и выберите город"));
            return;
        }
        inventory.setItem(10, info(Material.FILLED_MAP, application.name(), "ID: " + application.slug(),
                "Статус: " + statusLabel(application.status())));
        inventory.setItem(11, playerHead(application.founderId(), application.founderName(), null,
                "Предлагаемый основатель и первый мэр", "Подано: " + date(application.createdAt())));
        inventory.setItem(12, info(Material.WRITABLE_BOOK, "Описание", application.description(),
                "Одобрение создаст город без начальной эмиссии"));
        inventory.setItem(19, button(Material.LIME_CONCRETE, "Одобрить основание",
                "foundation_approve:" + application.id(), "Создать город и назначить основателя мэром",
                "Решение будет записано в аудит"));
        inventory.setItem(20, button(Material.RED_CONCRETE, "Отклонить заявку",
                "foundation_reject:" + application.id(), "Закрыть заявку без создания города",
                "Решение будет записано в аудит"));
    }

    private void renderEmission(Player player, Inventory inventory) {
        inventory.setItem(10, info(Material.RAW_GOLD, "Контролируемая эмиссия",
                "Получатель первого этапа: казна города",
                "Лимит операции: " + formatMinor(plugin.config().emissionMaxMinor())));
        inventory.setItem(11, loading("Загрузка городов и журнала…"));
        storage.submit(() -> new EmissionScreenData(cities.activeCities(), emission.recent(7)))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    for (int slot = 19; slot <= 34; slot++) inventory.setItem(slot, null);
                    if (error != null) { inventory.setItem(11, errorItem(error)); return; }
                    inventory.setItem(11, info(Material.GOLD_BLOCK, "Казны-получатели",
                            "Выберите город, затем укажите сумму и основание"));
                    int targetLimit = Math.min(7, data.cities().size());
                    for (int i = 0; i < targetLimit; i++) {
                        CityService.CitySummary city = data.cities().get(i);
                        inventory.setItem(19 + i, button(Material.GOLD_BLOCK, city.name(),
                                "emission_city:" + city.slug(), "Получатель: городская казна",
                                "ID города: " + city.slug(), "Далее: сумма и основание"));
                    }
                    if (targetLimit == 0) inventory.setItem(19, info(Material.MAP, "Нет активных городов",
                            "Эмиссию нельзя выполнить без казначейского счёта"));
                    int recent = 0;
                    for (EmissionService.Issue issue : data.issues()) {
                        if (recent >= 7) break;
                        inventory.setItem(28 + recent++, info(statusMaterial(issue.state()),
                                issue.targetName() + " · " + formatMinor(issue.amountMinor()),
                                "Состояние: " + statusLabel(issue.state()),
                                "Основание: " + shorten(issue.reason(), 42), "Дата: " + date(issue.createdAt())));
                    }
                    if (recent == 0) inventory.setItem(28, info(Material.PAPER, "История пуста",
                            "Выполненные операции появятся в этой строке"));
                }));
    }

    private void renderConfirmation(Player player, Inventory inventory) {
        Confirmation confirmation = confirmations.get(player.getUniqueId());
        if (confirmation == null) {
            inventory.setItem(10, info(Material.BARRIER, "Действие устарело",
                    "Вернитесь назад и выберите его ещё раз"));
            return;
        }
        inventory.setItem(10, info(Material.WRITABLE_BOOK, confirmation.title(), confirmation.description(),
                "Перед выполнением права и состояние проверятся снова"));
        inventory.setItem(19, button(Material.LIME_CONCRETE, confirmation.confirmLabel(), "confirm:run",
                "Подтвердить действие"));
        inventory.setItem(20, button(Material.RED_CONCRETE, "Отменить", "confirm:cancel",
                "Вернуться без изменений"));
    }

    private void renderNavigation(Player player, Route route, Inventory inventory) {
        if (route != Route.HOME) {
            inventory.setItem(GuiLayout.BACK_SLOT, button(Material.ARROW, "Назад", "back",
                    "Вернуться к предыдущему разделу"));
            inventory.setItem(GuiLayout.HOME_SLOT, button(Material.COMPASS, "Главная панель", "route:HOME",
                    "Открыть центр решений CityCore"));
        }
        inventory.setItem(GuiLayout.CLOSE_SLOT, button(Material.BARRIER, "Закрыть", "close", "Вернуться в игру"));
        storage.submit(() -> cities.view(player.getUniqueId())).whenComplete((city, error) ->
                sync(player, inventory, () -> renderAuthorityNavigation(player, inventory, city)));
    }

    private void renderAuthorityNavigation(Player player, Inventory inventory, CityService.CityView city) {
        for (int slot = 48; slot <= 50; slot++) inventory.setItem(slot, null);
        List<ItemStack> sections = new ArrayList<>();
        if (capabilities.allows(player, city, Capability.MAYOR_WORKSPACE)) {
            sections.add(button(Material.GOLDEN_HELMET, "Мэрия", "route:MAYOR",
                    "Рабочие реестры главы города"));
        }
        if (capabilities.allows(player, city, Capability.GOVERNMENT_WORKSPACE)) {
            sections.add(button(Material.SHIELD, "Городское управление", "route:GOVERNMENT",
                    "Реестры и решения чиновника"));
        }
        if (capabilities.allows(player, city, Capability.ADMIN_WORKSPACE)) {
            sections.add(button(Material.COMMAND_BLOCK, "Администрирование", "route:ADMIN",
                    "Система, города и эмиссия"));
        }
        int[] slots = GuiLayout.authoritySlots(sections.size());
        for (int i = 0; i < slots.length; i++) inventory.setItem(slots[i], sections.get(i));
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
        } else if (action.startsWith("command:")) {
            player.closeInventory();
            player.performCommand("cc " + action.substring(8));
        } else if (action.startsWith("city_apply:")) {
            String slug = action.substring("city_apply:".length());
            confirm(player, "Заявление в город «" + slug + "»",
                    "После отправки решение должен принять мэр.", "Подать заявление",
                    () -> { cities.apply(player.getUniqueId(), slug); return "Заявление отправлено мэру города."; }, Route.CITY);
        } else if (action.startsWith("foundation_view:")) {
            if (!capabilities.allows(player, null, Capability.REVIEW_CITY_FOUNDATIONS)) { UiText.error(player, "Нет доступа к заявкам городов."); return; }
            String id = action.substring("foundation_view:".length());
            storage.submit(cities::pendingFoundations).whenComplete((items, error) ->
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!player.isOnline()) return;
                        if (error != null) { failure(player, error); return; }
                        items.stream().filter(item -> item.id().equals(id)).findFirst().ifPresentOrElse(item -> {
                            selectedFoundation.put(player.getUniqueId(), item);
                            rememberReturn(player, Route.CITY_FOUNDATION_DETAIL, current);
                            navigate(player, Route.CITY_FOUNDATION_DETAIL);
                        }, () -> UiText.info(player, "Заявка уже обработана или удалена из очереди."));
                    }));
        } else if (action.startsWith("foundation_approve:") || action.startsWith("foundation_reject:")) {
            boolean approve = action.startsWith("foundation_approve:");
            String id = action.substring(action.indexOf(':') + 1);
            CityService.FoundationApplication selected = selectedFoundation.get(player.getUniqueId());
            if (selected == null || !selected.id().equals(id)) return;
            confirmPermission(player, approve ? "Одобрить основание города" : "Отклонить основание города",
                    approve ? "Будет создан город «" + selected.name() + "», а основатель станет мэром."
                            : "Заявка будет закрыта без создания города.",
                    approve ? "Создать город" : "Отклонить заявку", () -> {
                        CityService.FoundationDecision decision = cities.decideFoundation(player.getUniqueId(), id, approve,
                                approve ? "Одобрено через административную панель" : "Отклонено через административную панель");
                        if (approve) roleMirror.sync(selected.founderId(), CityRole.MAYOR);
                        return decision.approved() ? "Город «" + selected.name() + "» создан." : "Заявка отклонена.";
                    }, Route.CITY_FOUNDATIONS_ADMIN, "citycore.admin.cities");
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
                    () -> {
                        cities.decide(player.getUniqueId(), applicant, accept);
                        if (accept) roleMirror.sync(applicant, CityRole.CITIZEN);
                        return accept ? "Игрок принят в город." : "Заявление отклонено.";
                    },
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
                        roleMirror.sync(member, role);
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
        } else if (action.equals("prompt:city_foundation")) {
            prompts.begin(player, "Придумайте системный ID города (a-z, 0-9, _ или -):", slug ->
                    prompts.begin(player, "Введите отображаемое название города:", name ->
                            prompts.begin(player, "Кратко опишите идею города (8–180 символов):", description -> execute(player,
                                    () -> {
                                        cities.submitFoundation(player.getUniqueId(), slug, name, description);
                                        return "Заявка на основание города «" + name + "» отправлена администрации.";
                                    }, Route.CITY))));
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
        } else if (action.startsWith("emission_city:")) {
            if (!capabilities.allows(player, null, Capability.ISSUE_CURRENCY) || !plugin.config().emissionEnabled()) {
                UiText.error(player, "Эмиссия недоступна."); return;
            }
            String slug = action.substring("emission_city:".length());
            prompts.begin(player, "Введите сумму эмиссии для казны «" + slug + "»:", value -> {
                try {
                    long amount = MinorUnits.parse(value, plugin.config().currencyScale());
                    if (amount <= 0 || amount > plugin.config().emissionMaxMinor()) {
                        throw new IllegalArgumentException("Допустимый диапазон: 0.01–" + formatMinor(plugin.config().emissionMaxMinor()));
                    }
                    prompts.begin(player, "Укажите обязательное основание эмиссии:", reason ->
                            confirmPermission(player, "Эмиссия в казну «" + slug + "»",
                                    "Будет создано: " + formatMinor(amount) + ". Основание: " + shorten(reason, 70),
                                    "Выполнить эмиссию", () -> {
                                        emission.issueToCity(player.getUniqueId(), slug, amount, reason);
                                        return "Эмиссия выполнена: " + formatMinor(amount) + " → казна «" + slug + "».";
                                    }, Route.EMISSION, "citycore.admin.emission"));
                } catch (RuntimeException error) { failure(player, error); }
            });
        } else if (action.equals("confirm:run")) {
            Confirmation confirmation = confirmations.remove(player.getUniqueId());
            if (confirmation == null) return;
            if (confirmation.requiredPermission() != null
                    && !player.hasPermission(confirmation.requiredPermission())) {
                feedback.failure(player);
                UiText.error(player, "Полномочие было отозвано. Действие отменено.");
                navigate(player, confirmation.returnRoute());
                return;
            }
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
        selectedFoundation.remove(playerId);
        confirmations.remove(playerId);
        returnRoutes.remove(playerId);
    }

    private void confirm(Player player, String title, String description, String confirmLabel,
                         Callable<String> operation, Route returnRoute) {
        confirmations.put(player.getUniqueId(), new Confirmation(title, description, confirmLabel, operation, null, returnRoute, null));
        navigate(player, Route.CONFIRM);
    }

    private void confirmPermission(Player player, String title, String description, String confirmLabel,
                                   Callable<String> operation, Route returnRoute, String permission) {
        confirmations.put(player.getUniqueId(), new Confirmation(title, description, confirmLabel,
                operation, null, returnRoute, permission));
        navigate(player, Route.CONFIRM);
    }

    private void confirmMain(Player player, String title, String description, String confirmLabel,
                             Runnable operation, Route returnRoute) {
        confirmations.put(player.getUniqueId(), new Confirmation(title, description, confirmLabel, null, operation, returnRoute, null));
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
            case CITY_FOUNDATIONS_ADMIN, EMISSION -> Route.ADMIN;
            case CITY_FOUNDATION_DETAIL -> Route.CITY_FOUNDATIONS_ADMIN;
            case HELP, MAYOR, GOVERNMENT, ADMIN -> Route.HOME;
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

    private ItemStack lane(Material material, String name) {
        return info(material, name, "Раздел главной панели");
    }

    private ItemStack disabled(Material material, String name, String... reason) {
        String[] lore = Arrays.copyOf(reason, reason.length + 1);
        lore[lore.length - 1] = "Статус: недоступно";
        return info(material, name, lore);
    }

    private void renderFoundationShortcutAt(Inventory inventory, int slot, CityService.CityView city,
                                            CityService.FoundationApplication foundation) {
        if (city != null) {
            inventory.setItem(slot, disabled(Material.FILLED_MAP, "Основать город",
                    "Вы уже состоите в городе «" + city.name() + "»"));
        } else if (foundation != null && "PENDING".equals(foundation.status())) {
            inventory.setItem(slot, info(Material.WRITABLE_BOOK, "Основание · " + foundation.name(),
                    "Заявка ожидает решения администрации", "ID: " + foundation.slug(),
                    "Подано: " + date(foundation.createdAt())));
        } else {
            String previous = foundation != null && "REJECTED".equals(foundation.status())
                    ? "Предыдущая заявка была отклонена" : "Доступно игроку без гражданства";
            inventory.setItem(slot, button(Material.FILLED_MAP, "Основать город", "prompt:city_foundation",
                    "Подать заявку администрации", previous));
        }
    }

    private ItemStack button(Material material, String name, String action, String... lore) {
        ItemStack item = info(material, name, lore);
        item.editMeta(meta -> {
            List<Component> lines = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            lines.add(Component.empty());
            String hint = action.startsWith("route:") ? "ЛКМ  ›  открыть раздел"
                    : action.startsWith("prompt:") ? "ЛКМ  ›  начать заполнение"
                    : action.startsWith("command:") ? "ЛКМ  ›  выполнить"
                    : "ЛКМ  ›  выбрать";
            lines.add(UiText.hint(hint));
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
        for (int slot : GuiLayout.frameSlots()) {
            inventory.setItem(slot, (slot % 2 == 0) ? dark : gray);
        }
        inventory.setItem(4, info(Material.NETHER_STAR, "CityCore",
                "Техническая панель проекта", "Рабочие разделы и проверенные действия"));
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
            case "ACTIVE", "ACCEPTED", "APPROVED", "COMPLETED" -> Material.LIME_CONCRETE;
            case "PENDING", "WARNING", "EXPIRING" -> Material.YELLOW_CONCRETE;
            case "REJECTED", "REVOKED", "BLOCKED", "CLOSED", "FAILED" -> Material.RED_CONCRETE;
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
            case "APPROVED" -> "одобрено";
            case "COMPLETED" -> "выполнено";
            case "FAILED" -> "ошибка";
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
            case HOME -> "CityCore · Панель";
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
            case HELP -> "CityCore · Справка";
            case MAYOR -> "CityCore · Мэрия";
            case GOVERNMENT -> "CityCore · Госструктуры";
            case ADMIN -> "CityCore · Администрирование";
            case CITY_FOUNDATIONS_ADMIN -> "CityCore · Основание городов";
            case CITY_FOUNDATION_DETAIL -> "CityCore · Заявка на город";
            case EMISSION -> "CityCore · Эмиссия";
            case CONFIRM -> "CityCore · Подтверждение";
        };
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private String shorten(String value, int limit) {
        if (value == null) return "—";
        return value.length() <= limit ? value : value.substring(0, Math.max(1, limit - 1)) + "…";
    }

    private record Confirmation(String title, String description, String confirmLabel,
                                Callable<String> operation, Runnable mainOperation, Route returnRoute,
                                String requiredPermission) {}
    private record ProfileData(CityService.CityView city, List<BusinessService.BusinessView> businesses) {}
    private record HomeContext(CityService.CityView city, CityService.FoundationApplication foundation,
                               List<BusinessService.BusinessView> businesses) {}
    private record CityScreenData(CityService.CityView city, List<CityService.PlayerApplication> applications,
                                  CityService.FoundationApplication foundation) {}
    private record MemberScreenData(CityService.CityView city, List<CityService.Member> members) {}
    private record BusinessScreenData(CityService.CityView city, List<BusinessService.BusinessView> items) {}
    private record EconomyScreenData(CityService.CityView city, List<BusinessService.BusinessView> businesses) {}
    private record EmissionScreenData(List<CityService.CitySummary> cities, List<EmissionService.Issue> issues) {}
}
