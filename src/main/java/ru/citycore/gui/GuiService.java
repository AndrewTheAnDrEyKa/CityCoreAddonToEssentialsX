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
import ru.citycore.industry.IndustryService;
import ru.citycore.industry.ControllerItems;

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
    private final IndustryService industry;
    private final ControllerItems controllerItems;
    private final CapabilityService capabilities = new CapabilityService();
    private final NamespacedKey actionKey;
    private final Map<UUID, String> selectedBusiness = new ConcurrentHashMap<>();
    private final Map<UUID, CityService.Application> selectedApplication = new ConcurrentHashMap<>();
    private final Map<UUID, CityService.FoundationApplication> selectedFoundation = new ConcurrentHashMap<>();
    private final Map<UUID, Confirmation> confirmations = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedController = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedObject = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedLicenseType = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedVacantCity = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Route, Route>> returnRoutes = new ConcurrentHashMap<>();

    public GuiService(CityCorePlugin plugin, EconomyGateway economy, StorageExecutor storage, CityService cities,
                      BusinessService businesses, ChatPromptService prompts, GuiFeedback feedback,
                      VaultTransferCoordinator vaultTransfers, EmissionService emission, RoleMirror roleMirror) {
        this(plugin, economy, storage, cities, businesses, prompts, feedback, vaultTransfers, emission,
                roleMirror, null, null);
    }

    public GuiService(CityCorePlugin plugin, EconomyGateway economy, StorageExecutor storage, CityService cities,
                      BusinessService businesses, ChatPromptService prompts, GuiFeedback feedback,
                      VaultTransferCoordinator vaultTransfers, EmissionService emission, RoleMirror roleMirror,
                      IndustryService industry, ControllerItems controllerItems) {
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
        this.industry = industry;
        this.controllerItems = controllerItems;
        this.actionKey = new NamespacedKey(plugin, "gui_action");
    }

    public void open(Player player, Route route) {
        open(player, route, true);
    }

    public void openController(Player player, String serial) {
        boolean admin = player.hasPermission("citycore.admin.industry");
        storage.submit(() -> industry.controller(player.getUniqueId(), serial, admin)).whenComplete((controller, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (error != null) { failure(player, error); return; }
                    selectedController.put(player.getUniqueId(), serial);
                    selectedBusiness.put(player.getUniqueId(), controller.businessId());
                    if (controller.objectId() != null) {
                        selectedObject.put(player.getUniqueId(), controller.objectId());
                        open(player, Route.INDUSTRY_OBJECT);
                    } else open(player, Route.INDUSTRY_CONTROLLER);
                }));
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
        if ((route == Route.INDUSTRY_DEPOSITS) && !capabilities.allows(player, null, Capability.ADMIN_INDUSTRY)) {
            UiText.error(player, "У вас нет доступа к управлению месторождениями."); route = Route.HOME;
        }
        if ((route == Route.MAYOR_RESIGNATIONS_ADMIN || route == Route.MAYOR_VACANCIES_ADMIN
                || route == Route.MAYOR_APPOINT_ADMIN) && !player.hasPermission("citycore.admin.cities")) {
            UiText.error(player, "У вас нет доступа к управлению городскими должностями."); route = Route.HOME;
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
            case MAYOR_TRANSFER -> renderMayorTransfer(player, inventory);
            case MAYOR_TRANSFER_INBOX -> renderMayorTransferInbox(player, inventory);
            case MAYOR_RESIGNATIONS_ADMIN -> renderMayorResignationsAdmin(player, inventory);
            case MAYOR_VACANCIES_ADMIN -> renderMayorVacanciesAdmin(player, inventory);
            case MAYOR_APPOINT_ADMIN -> renderMayorAppointmentsAdmin(player, inventory);
            case LICENSE_TYPE -> renderLicenseTypes(player, inventory);
            case LICENSE_DURATION -> renderLicenseDuration(player, inventory);
            case INDUSTRY_BUSINESS -> renderIndustryBusiness(player, inventory);
            case INDUSTRY_CONTROLLER -> renderIndustryController(player, inventory);
            case INDUSTRY_DEPOSIT_PICK -> renderIndustryDepositPick(player, inventory);
            case INDUSTRY_OBJECT -> renderIndustryObject(player, inventory);
            case INDUSTRY_INSPECTIONS -> renderIndustryInspections(player, inventory);
            case INDUSTRY_DEPOSITS -> renderIndustryDeposits(player, inventory);
            case INDUSTRY_POLICY -> renderIndustryPolicy(player, inventory);
            case CONFIRM -> renderConfirmation(player, inventory);
        }
        renderNavigation(player, route, inventory);
    }

    private void renderHome(Player player, Inventory inventory) {
        int[] slots = GuiLayout.homeCategorySlots();
        inventory.setItem(slots[0], button(Material.PLAYER_HEAD, "Личное", "route:PROFILE",
                "Профиль, гражданство и собственные действия",
                "Открывает только личные функции"));
        inventory.setItem(slots[1], button(Material.BELL, "Город", "route:CITY",
                "Каталог, свой город и городские действия",
                "Состав зависит от вашего гражданства"));
        inventory.setItem(slots[2], button(Material.GOLD_INGOT, "Экономика", "route:ECONOMY",
                "Счета, предприятия и промышленность",
                "Рабочий центр владельца бизнеса"));
        inventory.setItem(slots[3], button(Material.COMPASS, "Сервисы", "route:HELP",
                "Справка и состояние технической панели",
                "Общие функции без RP-названий"));
    }

    private void renderProfile(Player player, Inventory inventory) {
        inventory.setItem(10, playerHead(player.getUniqueId(), player.getName(), null,
                "Личный раздел CityCore", "UUID хранится как основной идентификатор"));
        inventory.setItem(11, info(Material.GOLD_INGOT, "Личный кошелёк",
                formatMinor(economy.balanceMinor(player.getUniqueId())), "Источник: EssentialsX Economy"));
        inventory.setItem(11, loading("Загрузка связей профиля…"));
        storage.submit(() -> new PersonalData(cities.view(player.getUniqueId()), businesses.list(player.getUniqueId(), false),
                        cities.applications(player.getUniqueId()), cities.latestFoundation(player.getUniqueId()),
                        cities.incomingMayorTransfers(player.getUniqueId()), cities.outgoingMayorTransfers(player.getUniqueId()),
                        cities.pendingMayorResignation(player.getUniqueId())))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    if (error != null) {
                        inventory.setItem(11, errorItem(error));
                        return;
                    }
                    inventory.setItem(11, info(Material.GOLD_INGOT, "Личный кошелёк",
                            formatMinor(economy.balanceMinor(player.getUniqueId())), "Источник: EssentialsX Economy"));
                    if (data.city() == null) {
                        inventory.setItem(12, info(Material.MAP, "Без гражданства",
                                "Предприятия, имущество и долги при выходе сохраняются",
                                "Вступление доступно в разделе «Город»"));
                    } else {
                        inventory.setItem(12, button(Material.BELL, data.city().name(), "route:CITY",
                                "Должность: " + roleLabel(data.city().role()), "Город: " + data.city().slug()));
                    }
                    long owned = data.businesses().stream().filter(item -> item.ownerId().equals(player.getUniqueId())).count();
                    if (owned > 0) inventory.setItem(13, button(Material.BRICKS, "Мои предприятия · " + owned,
                            "route:BUSINESS", "Открыть деловой раздел"));
                    int action = 19;
                    if (!data.transfers().isEmpty()) {
                        inventory.setItem(action++, button(Material.GOLDEN_HELMET,
                                "Предложение стать мэром · " + data.transfers().size(), "route:MAYOR_TRANSFER_INBOX",
                                "Принять или отклонить передачу должности"));
                    }
                    if (!data.outgoingTransfers().isEmpty()) {
                        CityService.MayorTransfer outgoing = data.outgoingTransfers().getFirst();
                        inventory.setItem(action++, button(Material.RED_CONCRETE, "Отменить передачу должности",
                                "mayor_transfer_cancel:" + outgoing.id(), "Кандидат: " + outgoing.toId(),
                                "Роли пока не изменены"));
                    }
                    if (data.city() != null && data.city().role() == CityRole.CITIZEN) {
                        inventory.setItem(action++, button(Material.OAK_DOOR, "Покинуть город", "city_leave",
                                "Гражданство будет завершено", "Предприятия, имущество и долги сохранятся"));
                    } else if (data.city() != null && data.city().role() == CityRole.OFFICIAL) {
                        inventory.setItem(action++, button(Material.IRON_DOOR, "Сложить полномочия", "official_resign",
                                "Стать обычным гражданином", "После этого можно покинуть город"));
                    } else if (data.city() != null && data.city().role() == CityRole.MAYOR) {
                        if (data.outgoingTransfers().isEmpty() && data.mayorResignation() == null) inventory.setItem(action++, button(Material.GOLDEN_HELMET,
                                "Передать должность", "route:MAYOR_TRANSFER", "Предложить пост другому жителю"));
                        if (data.mayorResignation() == null && data.outgoingTransfers().isEmpty()) {
                            inventory.setItem(action++, button(Material.WRITABLE_BOOK, "Заявление об отставке", "mayor_resign_prompt",
                                    "Используется, если подходящего преемника нет", "Решение принимает администратор"));
                        } else if (data.mayorResignation() != null) {
                            inventory.setItem(action++, button(Material.RED_CONCRETE, "Отозвать заявление об отставке",
                                    "mayor_resignation_cancel:" + data.mayorResignation().id(),
                                    "Администрация ещё не приняла решение", "Причина: " + shorten(data.mayorResignation().reason(), 50)));
                        }
                    }
                    for (CityService.PlayerApplication application : data.applications()) {
                        if (!"PENDING".equals(application.status()) || action > 25) continue;
                        inventory.setItem(action++, button(Material.WRITABLE_BOOK, "Отозвать заявление · " + application.cityName(),
                                "citizenship_cancel:" + application.id(), "Заявление ещё не рассмотрено"));
                    }
                    if (data.foundation() != null && "PENDING".equals(data.foundation().status()) && action <= 25) {
                        inventory.setItem(action, button(Material.FILLED_MAP, "Отозвать основание · " + data.foundation().name(),
                                "foundation_cancel:" + data.foundation().id(), "Заявка будет закрыта без создания города"));
                    }
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
                        if (data.city() != null) inventory.setItem(37, button(Material.CRAFTING_TABLE, "Создать предприятие",
                                "prompt:business_register", "Подать заявку на регистрацию компании"));
                        boolean official = data.city() != null && (data.city().role() == CityRole.MAYOR || data.city().role() == CityRole.OFFICIAL);
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
                            statusLabel(business.status()), "Деятельность: " + activityLabel(business.activityType()),
                            business.accountId() == null ? "Счёт ещё не открыт" : "Внутренний счёт открыт"));
                    if (business.balanceMinor() != null) inventory.setItem(13, info(Material.GOLD_INGOT,
                            "Счёт предприятия", formatMinor(business.balanceMinor()), "Доступен владельцу и городской власти"));

                    int actionSlot = 19;
                    if (business.owner() && business.accountId() != null) {
                        inventory.setItem(actionSlot++, button(Material.HOPPER, "Пополнить предприятие",
                                "business_deposit:" + business.id(), "Перевести деньги из личного кошелька"));
                    }
                    if (business.owner() && "PENDING".equals(business.status())) {
                        inventory.setItem(actionSlot++, button(Material.RED_CONCRETE, "Отозвать регистрацию",
                                "business_withdraw:" + business.id(), "Закрыть ещё не рассмотренную заявку"));
                    }
                    if (business.owner() && "ACTIVE".equals(business.status())) {
                        if ("GENERAL".equals(business.activityType())) {
                            inventory.setItem(actionSlot++, button(Material.BUCKET, "Выбрать нефтедобычу",
                                    "business_oil_activity:" + business.id(), "Перевести предприятие в нефтяную отрасль",
                                    "После создания объекта деятельность фиксируется"));
                        } else if ("OIL_EXTRACTION".equals(business.activityType())) {
                            inventory.setItem(actionSlot++, button(Material.PISTON, "Нефтяные объекты",
                                    "industry_business:" + business.id(), "Контроллеры, заявки и действующие установки"));
                        }
                    }
                    if (business.official() && "PENDING".equals(business.status())) {
                        inventory.setItem(actionSlot++, button(Material.LIME_CONCRETE, "Одобрить регистрацию",
                                "business_approve:" + business.id(), "Активировать предприятие и открыть счёт"));
                        inventory.setItem(actionSlot, button(Material.RED_CONCRETE, "Отклонить регистрацию",
                                "business_reject:" + business.id(), "Закрыть заявление без активации"));
                    } else if (business.official() && "ACTIVE".equals(business.status())) {
                        inventory.setItem(actionSlot, button(Material.ENCHANTED_BOOK, "Выдать лицензию",
                                "license_choose:" + business.id(), "Выбрать тип и срок разрешения без технического ввода"));
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
                        inventory.setItem(11, info(Material.MAP, "Без городской казны",
                                "Гражданство не активно", "Собственные предприятия сохраняют доступные счета"));
                    } else {
                        inventory.setItem(12, info(Material.GOLD_BLOCK, "Казна · " + data.city().name(),
                                formatMinor(data.city().treasuryMinor()), "Общий счёт города"));
                        inventory.setItem(13, button(Material.HOPPER, "Пополнить городскую казну",
                                "prompt:treasury_deposit", "Перевести личные средства своему городу"));
                    }
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
                    inventory.setItem(14, button(Material.BRICKS, "Все предприятия", "route:BUSINESS",
                            "Карточки, счета и нефтяные объекты"));
                }));
    }

    private void renderHelp(Inventory inventory) {
        inventory.setItem(10, info(Material.COMPASS, "Навигация",
                "Главная панель содержит четыре тематических перехода", "Рабочие кнопки идут слева направо"));
        inventory.setItem(11, info(Material.NAME_TAG, "Личное",
                "Профиль, гражданство, отставка и собственные заявления"));
        inventory.setItem(12, info(Material.BELL, "Город",
                "Каталог, гражданство, основание и жители"));
        inventory.setItem(13, info(Material.GOLD_INGOT, "Экономика",
                "Счета, предприятия и нефтяная промышленность"));
        inventory.setItem(14, info(Material.COMMAND_BLOCK, "Полномочия",
                "Доступные рабочие разделы собраны внизу", "Роль повторно проверяется перед каждым действием"));
        inventory.setItem(19, info(Material.WRITABLE_BOOK, "Обратные действия",
                "Ожидающие заявления можно отозвать", "Выход и отставка сохраняют историю и обязательства"));
        inventory.setItem(20, info(Material.ENCHANTED_BOOK, "Лицензии",
                "Тип и срок выбираются кнопками", "Технический код вводить не требуется"));
        inventory.setItem(21, info(Material.BUCKET, "Нефтяной объект",
                "Экономика → Предприятия → Карточка → Нефтяные объекты"));
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
                    inventory.setItem(19, button(Material.BUCKET, "Промышленная политика", "route:INDUSTRY_POLICY",
                            "Закупочный фонд и налог на нефтедобычу"));
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
                    inventory.setItem(15, button(Material.SPYGLASS, "Промышленные осмотры", "route:INDUSTRY_INSPECTIONS",
                            "Проверить нефтяные объекты и назначить уровень"));
                }));
    }

    private void renderAdmin(Player player, Inventory inventory) {
        inventory.setItem(10, info(Material.COMMAND_BLOCK, "Администрирование",
                "Административный центр", "Схема базы данных: 5"));
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
            inventory.setItem(slot++, button(Material.RAW_GOLD, "Эмиссия", "route:EMISSION",
                    "Пополнение казны через ledger", "Каждая операция требует основания"));
        }
        if (player.hasPermission("citycore.admin.cities")) {
            inventory.setItem(slot++, button(Material.WRITABLE_BOOK, "Отставки мэров", "route:MAYOR_RESIGNATIONS_ADMIN",
                    "Рассмотреть заявления без преемника"));
            inventory.setItem(slot++, button(Material.GOLDEN_HELMET, "Вакансии мэров", "route:MAYOR_VACANCIES_ADMIN",
                    "Назначить главу городу после одобренной отставки"));
        }
        if (capabilities.allows(player, null, Capability.ADMIN_INDUSTRY)) {
            inventory.setItem(slot > 16 ? 19 : slot, button(Material.RAW_IRON, "Месторождения", "route:INDUSTRY_DEPOSITS",
                    "Создание и диагностика конечных запасов нефти"));
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

    private void renderMayorTransfer(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Загрузка кандидатов…"));
        storage.submit(() -> new MemberScreenData(cities.view(player.getUniqueId()), cities.members(player.getUniqueId())))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    clearList(inventory);
                    if (error != null) { inventory.setItem(22, errorItem(error)); return; }
                    if (data.city() == null || data.city().role() != CityRole.MAYOR) {
                        inventory.setItem(22, disabled(Material.BARRIER, "Передача недоступна", "Требуется должность мэра")); return;
                    }
                    int index = 0;
                    for (CityService.Member member : data.members()) {
                        if (member.playerId().equals(player.getUniqueId()) || index >= LIST_SLOTS.length) continue;
                        inventory.setItem(LIST_SLOTS[index++], playerHead(member.playerId(), member.playerName(),
                                "mayor_transfer_request:" + member.playerId(), "Текущая роль: " + roleLabel(member.role()),
                                "Игрок должен отдельно принять предложение"));
                    }
                    if (index == 0) inventory.setItem(22, info(Material.PAPER, "Нет кандидатов",
                            "Подайте административное заявление об отставке в личном разделе"));
                }));
    }

    private void renderMayorTransferInbox(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Загрузка предложений…"));
        storage.submit(() -> cities.incomingMayorTransfers(player.getUniqueId()))
                .whenComplete((items, error) -> sync(player, inventory, () -> {
                    clearList(inventory);
                    if (error != null) { inventory.setItem(22, errorItem(error)); return; }
                    int slot = 10;
                    for (CityService.MayorTransfer item : items) {
                        if (slot > 42) break;
                        inventory.setItem(slot++, button(Material.LIME_CONCRETE, "Принять должность мэра",
                                "mayor_transfer_accept:" + item.id(), "Город: " + item.cityId(),
                                "Прежний мэр станет гражданином"));
                        inventory.setItem(slot++, button(Material.RED_CONCRETE, "Отклонить предложение",
                                "mayor_transfer_reject:" + item.id(), "Город: " + item.cityId(), "Роли не изменятся"));
                    }
                    if (items.isEmpty()) inventory.setItem(22, info(Material.PAPER, "Предложений нет",
                            "Новых запросов на передачу должности не найдено"));
                }));
    }

    private void renderMayorResignationsAdmin(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Загрузка заявлений об отставке…"));
        storage.submit(cities::pendingMayorResignations).whenComplete((items, error) -> sync(player, inventory, () -> {
            clearList(inventory);
            if (error != null) { inventory.setItem(22, errorItem(error)); return; }
            int slot = 10;
            for (CityService.MayorResignation item : items) {
                if (slot > 41) break;
                inventory.setItem(slot++, info(Material.WRITABLE_BOOK, "Заявление мэра", "Город: " + item.cityId(),
                        "Мэр: " + item.mayorId(), "Причина: " + shorten(item.reason(), 60)));
                inventory.setItem(slot++, button(Material.LIME_CONCRETE, "Одобрить отставку",
                        "mayor_resignation_approve:" + item.id(), "Город получит статус вакансии"));
                inventory.setItem(slot++, button(Material.RED_CONCRETE, "Отклонить отставку",
                        "mayor_resignation_reject:" + item.id(), "Мэр сохранит должность"));
            }
            if (items.isEmpty()) inventory.setItem(22, info(Material.PAPER, "Очередь пуста", "Заявлений об отставке нет"));
        }));
    }

    private void renderMayorVacanciesAdmin(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Загрузка городов без мэра…"));
        storage.submit(cities::vacantCities).whenComplete((items, error) -> sync(player, inventory, () -> {
            clearList(inventory);
            if (error != null) { inventory.setItem(22, errorItem(error)); return; }
            for (int i = 0; i < items.size() && i < LIST_SLOTS.length; i++) {
                CityService.CitySummary city = items.get(i);
                inventory.setItem(LIST_SLOTS[i], button(Material.GOLDEN_HELMET, city.name(),
                        "mayor_vacancy:" + city.id(), "ID: " + city.slug(), "Жителей: " + city.citizenCount(),
                        "Выбрать нового мэра"));
            }
            if (items.isEmpty()) inventory.setItem(22, info(Material.PAPER, "Вакансий нет", "Во всех городах назначен мэр"));
        }));
    }

    private void renderMayorAppointmentsAdmin(Player player, Inventory inventory) {
        String cityId = selectedVacantCity.get(player.getUniqueId());
        if (cityId == null) { inventory.setItem(22, disabled(Material.BARRIER, "Город не выбран", "Вернитесь к списку вакансий")); return; }
        inventory.setItem(22, loading("Загрузка жителей…"));
        storage.submit(() -> cities.membersForAdmin(cityId)).whenComplete((items, error) -> sync(player, inventory, () -> {
            clearList(inventory);
            if (error != null) { inventory.setItem(22, errorItem(error)); return; }
            for (int i = 0; i < items.size() && i < LIST_SLOTS.length; i++) {
                CityService.Member member = items.get(i);
                inventory.setItem(LIST_SLOTS[i], playerHead(member.playerId(), member.playerName(),
                        "mayor_appoint:" + member.playerId(), "Текущая роль: " + roleLabel(member.role()),
                        "Назначение восстановит активное управление городом"));
            }
        }));
    }

    private void renderLicenseTypes(Player player, Inventory inventory) {
        String businessId = selectedBusiness.get(player.getUniqueId());
        if (businessId == null) { inventory.setItem(22, disabled(Material.BARRIER, "Предприятие не выбрано", "Вернитесь к карточке предприятия")); return; }
        inventory.setItem(10, info(Material.ENCHANTED_BOOK, "Выберите тип лицензии",
                "Технический код больше не требуется вводить в чат"));
        inventory.setItem(19, button(Material.PAPER, "Торговая лицензия", "license_type:TRADE", "Код: TRADE"));
        inventory.setItem(20, button(Material.BUCKET, "Нефтедобыча I", "license_type:OIL_EXTRACTION_I", "Для объекта уровня I"));
        inventory.setItem(21, button(Material.BUCKET, "Нефтедобыча II", "license_type:OIL_EXTRACTION_II", "Для объекта уровня II"));
        inventory.setItem(22, button(Material.BUCKET, "Нефтедобыча III", "license_type:OIL_EXTRACTION_III", "Для объекта уровня III"));
    }

    private void renderLicenseDuration(Player player, Inventory inventory) {
        String type = selectedLicenseType.get(player.getUniqueId());
        if (type == null || selectedBusiness.get(player.getUniqueId()) == null) {
            inventory.setItem(22, disabled(Material.BARRIER, "Тип не выбран", "Вернитесь к выбору лицензии")); return;
        }
        int minimum = minimumLicenseDays(type);
        inventory.setItem(10, info(Material.CLOCK, "Срок лицензии", "Тип: " + type,
                "Минимальный срок: " + minimum + " дней", "Выберите срок действия"));
        if (minimum <= 7) inventory.setItem(19, button(Material.PAPER, "7 дней", "license_days:7", "Короткий испытательный срок"));
        if (minimum <= 30) inventory.setItem(20, button(Material.PAPER, "30 дней", "license_days:30", "Один календарный месяц"));
        if (minimum <= 90) inventory.setItem(21, button(Material.PAPER, "90 дней", "license_days:90", "Три месяца"));
        inventory.setItem(22, button(Material.PAPER, "365 дней", "license_days:365", "Один год"));
        inventory.setItem(23, button(Material.WRITABLE_BOOK, "Другой срок", "license_days:custom", "Введите от 1 до 3650 дней"));
    }

    private void renderIndustryBusiness(Player player, Inventory inventory) {
        String businessId = selectedBusiness.get(player.getUniqueId());
        if (businessId == null) { inventory.setItem(22, disabled(Material.BARRIER, "Предприятие не выбрано", "Откройте карточку предприятия")); return; }
        inventory.setItem(22, loading("Загрузка промышленного контура…"));
        boolean admin = player.hasPermission("citycore.admin.industry");
        storage.submit(() -> new IndustryBusinessData(businesses.detail(player.getUniqueId(), businessId),
                        industry.objects(player.getUniqueId(), businessId, admin),
                        industry.controllers(player.getUniqueId(), businessId)))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    clearList(inventory);
                    if (error != null) { inventory.setItem(22, errorItem(error)); return; }
                    inventory.setItem(10, info(Material.BUCKET, data.business().name(),
                            "Деятельность: " + activityLabel(data.business().activityType()),
                            "Объектов: " + data.objects().size() + " · контроллеров: " + data.controllers().size()));
                    int objectIndex = 0;
                    for (IndustryService.ObjectView object : data.objects()) {
                        if (objectIndex >= 7) break;
                        inventory.setItem(19 + objectIndex++, button(statusMaterial(object.status()),
                                "Нефтяной объект" + (object.level() == null ? "" : " · " + roman(object.level())),
                                "industry_object:" + object.id(), "Состояние: " + statusLabel(object.status()),
                                "Месторождение: " + object.depositName(), "Остаток: " + object.reserveRemaining() + " ед."));
                    }
                    if (objectIndex == 0) inventory.setItem(19, info(Material.PAPER, "Объектов пока нет",
                            "Получите и разместите контроллер, затем подайте заявку"));
                    int controllerIndex = 0;
                    for (IndustryService.ControllerView controller : data.controllers()) {
                        if (controllerIndex >= 7) break;
                        inventory.setItem(28 + controllerIndex++, button(plugin.config().industry().controllerMaterial(),
                                controller.serial(), "industry_controller:" + controller.serial(),
                                "Состояние: " + statusLabel(controller.state()),
                                controller.worldName() == null ? "Контроллер ещё не размещён" : "Точка: " + controller.worldName() + " " + controller.x() + ", " + controller.y() + ", " + controller.z()));
                    }
                    if (data.business().owner() && "ACTIVE".equals(data.business().status())) {
                        inventory.setItem(37, button(Material.LODESTONE, "Получить новый контроллер",
                                "controller_issue:" + businessId, "Создать уникальный серийный номер",
                                "Предмет будет добавлен в инвентарь"));
                    }
                }));
    }

    private void renderIndustryController(Player player, Inventory inventory) {
        String serial = selectedController.get(player.getUniqueId());
        if (serial == null) { inventory.setItem(22, disabled(Material.BARRIER, "Контроллер не выбран", "Откройте его из предприятия или нажмите ПКМ")); return; }
        boolean admin = player.hasPermission("citycore.admin.industry");
        inventory.setItem(22, loading("Проверяем контроллер…"));
        storage.submit(() -> industry.controller(player.getUniqueId(), serial, admin)).whenComplete((value, error) -> sync(player, inventory, () -> {
            clearList(inventory);
            if (error != null) { inventory.setItem(22, errorItem(error)); return; }
            selectedBusiness.put(player.getUniqueId(), value.businessId());
            inventory.setItem(10, info(plugin.config().industry().controllerMaterial(), "Контроллер " + value.serial(),
                    "Предприятие: " + value.businessName(), "Состояние: " + statusLabel(value.state()),
                    value.worldName() == null ? "Не размещён" : value.worldName() + " · " + value.x() + ", " + value.y() + ", " + value.z()));
            if (value.objectId() != null) {
                inventory.setItem(19, button(Material.PISTON, "Открыть связанный объект",
                        "industry_object:" + value.objectId(), "Карточка производства и расчётные циклы"));
                if ("PLACED".equals(value.state())) inventory.setItem(20, button(Material.CHAIN, "Восстановить привязку",
                        "controller_rebind:" + value.serial(), "Вернуть объект в состояние паузы после установки"));
            } else if ("PLACED".equals(value.state())) {
                inventory.setItem(19, button(Material.WRITABLE_BOOK, "Подать заявку на объект",
                        "route:INDUSTRY_DEPOSIT_PICK", "Выбрать месторождение для этого контроллера"));
            } else if ("ISSUED".equals(value.state())) {
                inventory.setItem(19, info(Material.OAK_SIGN, "Сначала установите контроллер",
                        "Поставьте предмет как блок в выбранной точке", "Затем нажмите по нему ПКМ"));
            }
        }));
    }

    private void renderIndustryDepositPick(Player player, Inventory inventory) {
        String serial = selectedController.get(player.getUniqueId()); String businessId = selectedBusiness.get(player.getUniqueId());
        if (serial == null || businessId == null) { inventory.setItem(22, disabled(Material.BARRIER, "Контроллер не выбран", "Вернитесь к размещённому контроллеру")); return; }
        inventory.setItem(22, loading("Загрузка доступных месторождений…"));
        storage.submit(() -> industry.depositsForBusiness(player.getUniqueId(), businessId)).whenComplete((items, error) -> sync(player, inventory, () -> {
            clearList(inventory);
            if (error != null) { inventory.setItem(22, errorItem(error)); return; }
            for (int i = 0; i < items.size() && i < LIST_SLOTS.length; i++) {
                IndustryService.DepositView deposit = items.get(i);
                inventory.setItem(LIST_SLOTS[i], button(Material.RAW_IRON, deposit.name(),
                        "industry_apply:" + deposit.id(), "ID: " + deposit.slug(),
                        "Запас: " + deposit.reserveRemaining() + " / " + deposit.reserveTotal(),
                        "Лимит периода: " + deposit.throughputLimit()));
            }
            if (items.isEmpty()) inventory.setItem(22, info(Material.PAPER, "Нет доступных месторождений",
                    "Администратор должен зарегистрировать ресурсный участок города"));
        }));
    }

    private void renderIndustryObject(Player player, Inventory inventory) {
        String objectId = selectedObject.get(player.getUniqueId());
        if (objectId == null) { inventory.setItem(22, disabled(Material.BARRIER, "Объект не выбран", "Вернитесь к списку объектов")); return; }
        boolean admin = player.hasPermission("citycore.admin.industry");
        inventory.setItem(22, loading("Загрузка промышленного объекта…"));
        storage.submit(() -> new IndustryObjectData(industry.object(player.getUniqueId(), objectId, admin),
                        industry.recentCycles(player.getUniqueId(), objectId, admin, 7)))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    clearList(inventory);
                    if (error != null) { inventory.setItem(22, errorItem(error)); return; }
                    IndustryService.ObjectView object = data.object();
                    selectedBusiness.put(player.getUniqueId(), object.businessId());
                    inventory.setItem(10, info(statusMaterial(object.status()), "Нефтяной объект" + (object.level() == null ? "" : " · " + roman(object.level())),
                            "Предприятие: " + object.businessName(), "Состояние: " + statusLabel(object.status()),
                            object.lastError().isBlank() ? "Ошибок нет" : "Причина: " + shorten(object.lastError(), 60)));
                    inventory.setItem(11, info(Material.RAW_IRON, object.depositName(),
                            "Запас: " + object.reserveRemaining() + " / " + object.reserveTotal(),
                            "Лимит периода: " + object.throughputLimit()));
                    inventory.setItem(12, button(plugin.config().industry().controllerMaterial(), object.controllerSerial(),
                            "industry_controller:" + object.controllerSerial(),
                            "Контроллер: " + statusLabel(object.controllerState()),
                            "Лицензия: " + (object.level() == null ? "после инспекции" : "OIL_EXTRACTION_" + roman(object.level())),
                            "Открыть размещение и перепривязку"));
                    inventory.setItem(13, info(Material.GOLD_INGOT, "Финансовое состояние",
                            "Счёт: " + formatMinor(object.businessBalanceMinor()), "Долг: " + formatMinor(object.debtMinor()),
                            "Аренда за период: " + formatMinor(object.leaseMinor())));
                    inventory.setItem(14, info(Material.CLOCK, "Расчётный период",
                            "Следующий: " + dateTime(object.nextCycleAt()), "Последний: " + dateTime(object.lastCycleAt())));

                    int action = 19;
                    if (object.owner()) {
                        if ("DRAFT".equals(object.status())) inventory.setItem(action++, button(Material.WRITABLE_BOOK,
                                "Подать на осмотр", "industry_submit:" + object.id(), "Передать объект городскому инспектору"));
                        else if ("PENDING_INSPECTION".equals(object.status())) inventory.setItem(action++, button(Material.RED_CONCRETE,
                                "Отозвать заявку", "industry_withdraw:" + object.id(), "Вернуть контроллер в свободное состояние"));
                        else if ("REJECTED".equals(object.status()) || "CANCELLED".equals(object.status())) inventory.setItem(action++, button(Material.WRITABLE_BOOK,
                                "Подать повторно", "industry_resubmit:" + object.id(), "После исправления замечаний инспектора"));
                        else if ("ACTIVE".equals(object.status())) inventory.setItem(action++, button(Material.LEVER,
                                "Остановить производство", "industry_pause:" + object.id(), "Обязательные платежи продолжат учитываться"));
                        else if (object.level() != null && !"DECOMMISSIONED".equals(object.status()) && !"DEPLETED".equals(object.status())) inventory.setItem(action++, button(Material.LIME_CONCRETE,
                                "Запустить производство", "industry_start:" + object.id(), "Проверить лицензию, контроллер, долг и запас"));
                        if (!"DECOMMISSIONED".equals(object.status())) inventory.setItem(action, button(Material.BARRIER,
                                "Вывести из эксплуатации", "industry_decommission:" + object.id(), "Необратимо остановить новые циклы", "Долг и история сохранятся"));
                    }
                    if (object.official() && "PENDING_INSPECTION".equals(object.status())) {
                        inventory.setItem(28, button(Material.COPPER_INGOT, "Одобрить уровень I", "industry_inspect:" + object.id() + ":1", "Малая установка"));
                        inventory.setItem(29, button(Material.IRON_INGOT, "Одобрить уровень II", "industry_inspect:" + object.id() + ":2", "Промышленная установка"));
                        inventory.setItem(30, button(Material.GOLD_INGOT, "Одобрить уровень III", "industry_inspect:" + object.id() + ":3", "Крупный комплекс"));
                        inventory.setItem(31, button(Material.RED_CONCRETE, "Отклонить проверку", "industry_inspect_reject:" + object.id(), "Указать причину отказа"));
                    }
                    int cycle = 0;
                    for (IndustryService.CycleView value : data.cycles()) {
                        if (cycle >= 7) break;
                        inventory.setItem(37 + cycle++, info(statusMaterial(value.state()), dateTime(value.dueAt()),
                                "Состояние: " + statusLabel(value.state()), "Добыто: " + value.extractedUnits() + " ед.",
                                "Выручка: " + formatMinor(value.grossMinor()), "Результат: " + formatMinor(value.netMinor())));
                    }
                }));
    }

    private void renderIndustryInspections(Player player, Inventory inventory) {
        boolean admin = player.hasPermission("citycore.admin.industry");
        inventory.setItem(22, loading("Загрузка очереди осмотров…"));
        storage.submit(() -> industry.pendingInspections(player.getUniqueId(), admin)).whenComplete((items, error) -> sync(player, inventory, () -> {
            clearList(inventory);
            if (error != null) { inventory.setItem(22, errorItem(error)); return; }
            for (int i = 0; i < items.size() && i < LIST_SLOTS.length; i++) {
                IndustryService.ObjectView object = items.get(i);
                inventory.setItem(LIST_SLOTS[i], button(Material.SPYGLASS, object.businessName(),
                        "industry_object:" + object.id(), "Месторождение: " + object.depositName(),
                        "Контроллер: " + object.controllerSerial(), "Подано: " + date(object.submittedAt())));
            }
            if (items.isEmpty()) inventory.setItem(22, info(Material.PAPER, "Очередь пуста", "Новых объектов на проверку нет"));
        }));
    }

    private void renderIndustryDeposits(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Загрузка месторождений…"));
        storage.submit(() -> new AdminIndustryData(industry.deposits(player.getUniqueId(), true),
                        industry.diagnosticObjects(player.getUniqueId(), true)))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
            clearList(inventory);
            if (error != null) { inventory.setItem(22, errorItem(error)); return; }
            for (int i = 0; i < data.deposits().size() && i < 14; i++) {
                IndustryService.DepositView deposit = data.deposits().get(i);
                inventory.setItem(LIST_SLOTS[i], info(statusMaterial(deposit.status()), deposit.name(),
                        "ID: " + deposit.slug(), "Город: " + deposit.cityId(),
                        "Запас: " + deposit.reserveRemaining() + " / " + deposit.reserveTotal(),
                        "Участок: " + (deposit.assignedBusinessName() == null ? "свободен" : deposit.assignedBusinessName())));
            }
            for (int i = 0; i < data.objects().size() && i < 7; i++) {
                IndustryService.ObjectView object = data.objects().get(i);
                inventory.setItem(28 + i, button(statusMaterial(object.status()), object.businessName(),
                        "industry_object:" + object.id(), "Объект: " + object.id().substring(0, 8),
                        "Состояние: " + statusLabel(object.status()), "Долг: " + formatMinor(object.debtMinor()),
                        "Открыть циклы и контроллер"));
            }
            if (data.deposits().isEmpty()) inventory.setItem(10, info(Material.PAPER, "Месторождений пока нет",
                    "Создайте первое месторождение кнопкой ниже"));
            if (data.objects().isEmpty()) inventory.setItem(28, info(Material.COMPARATOR, "Промышленных объектов пока нет",
                    "Последние объекты и их состояния появятся в этой строке"));
            inventory.setItem(37, button(Material.RAW_IRON, "Создать месторождение", "deposit_create",
                    "Использовать текущую позицию администратора", "Потребуются город, ID, название, радиус и запас"));
        }));
    }

    private void renderIndustryPolicy(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Загрузка промышленной политики…"));
        storage.submit(() -> industry.policy(player.getUniqueId())).whenComplete((policy, error) -> sync(player, inventory, () -> {
            clearList(inventory);
            if (error != null) { inventory.setItem(22, errorItem(error)); return; }
            inventory.setItem(10, info(Material.GOLD_BLOCK, "Промышленный закупочный фонд",
                    "Баланс: " + formatMinor(policy.procurementBalanceMinor()),
                    "Нефть оплачивается только с этого счёта"));
            inventory.setItem(11, info(Material.PAPER, "Промышленный налог",
                    "Текущая ставка: " + percent(policy.taxBps()), "Максимум: " + percent(policy.maxTaxBps())));
            inventory.setItem(19, button(Material.HOPPER, "Пополнить закупочный фонд", "procurement_fund",
                    "Перевести средства из городской казны"));
            int[] rates = {0, 500, 1000, 1500, 2000, 2500};
            int slot = 28;
            for (int rate : rates) {
                if (rate > policy.maxTaxBps()) continue;
                inventory.setItem(slot++, button(Material.PAPER, "Налог " + percent(rate), "industry_tax:" + rate,
                        "Новая ставка применяется к будущим циклам"));
            }
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
        for (int slot = 47; slot <= 51; slot++) inventory.setItem(slot, null);
        List<ItemStack> sections = new ArrayList<>();
        if (capabilities.allows(player, city, Capability.MAYOR_WORKSPACE)) {
            sections.add(button(Material.GOLDEN_HELMET, "Мэрия", "route:MAYOR",
                    "Рабочие реестры главы города"));
        }
        if (capabilities.allows(player, city, Capability.GOVERNMENT_WORKSPACE)) {
            sections.add(button(Material.SHIELD, "Городское управление", "route:GOVERNMENT",
                    "Реестры и решения чиновника"));
        }
        if (plugin.config().industry().enabled() && capabilities.allows(player, city, Capability.MANAGE_INDUSTRY)) {
            sections.add(button(Material.SPYGLASS, "Промышленный контроль", "route:INDUSTRY_INSPECTIONS",
                    "Осмотры нефтяных объектов"));
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
        } else if (action.equals("city_leave")) {
            confirm(player, "Покинуть город", "Предприятия, имущество, лицензии и долги сохранятся. Повторное вступление будет ограничено cooldown.",
                    "Покинуть город", () -> {
                        cities.leaveCity(player.getUniqueId()); roleMirror.sync(player.getUniqueId(), null);
                        return "Гражданство завершено. Ваши экономические обязательства сохранены.";
                    }, Route.PROFILE);
        } else if (action.equals("official_resign")) {
            confirm(player, "Сложить полномочия", "Вы станете обычным гражданином и потеряете служебные capabilities.",
                    "Сложить полномочия", () -> {
                        cities.resignOfficial(player.getUniqueId()); roleMirror.sync(player.getUniqueId(), CityRole.CITIZEN);
                        return "Полномочия чиновника сложены.";
                    }, Route.PROFILE);
        } else if (action.startsWith("citizenship_cancel:")) {
            String id = action.substring("citizenship_cancel:".length());
            confirm(player, "Отозвать заявление", "Мэр больше не сможет принять это заявление.", "Отозвать",
                    () -> { cities.cancelCitizenshipApplication(player.getUniqueId(), id); return "Заявление отозвано."; }, Route.PROFILE);
        } else if (action.startsWith("foundation_cancel:")) {
            String id = action.substring("foundation_cancel:".length());
            confirm(player, "Отозвать основание города", "Заявка будет закрыта без создания города.", "Отозвать",
                    () -> { cities.cancelFoundation(player.getUniqueId(), id); return "Заявка на основание отозвана."; }, Route.PROFILE);
        } else if (action.startsWith("mayor_transfer_request:")) {
            UUID candidate = uuid(action.substring("mayor_transfer_request:".length())); if (candidate == null) return;
            confirm(player, "Предложить должность мэра", "Кандидат должен отдельно принять предложение. До этого роли не изменятся.",
                    "Отправить предложение", () -> {
                        cities.requestMayorTransfer(player.getUniqueId(), candidate); return "Предложение кандидату отправлено.";
                    }, Route.PROFILE);
        } else if (action.startsWith("mayor_transfer_accept:") || action.startsWith("mayor_transfer_reject:")) {
            boolean accept = action.startsWith("mayor_transfer_accept:"); String id = action.substring(action.indexOf(':') + 1);
            confirm(player, accept ? "Принять должность мэра" : "Отклонить должность мэра",
                    accept ? "Передача выполнится атомарно: прежний мэр станет гражданином." : "Роли участников не изменятся.",
                    accept ? "Принять должность" : "Отклонить", () -> {
                        CityService.MayorTransfer value = cities.decideMayorTransfer(player.getUniqueId(), id, accept);
                        if (accept) { roleMirror.sync(value.fromId(), CityRole.CITIZEN); roleMirror.sync(value.toId(), CityRole.MAYOR); }
                        return accept ? "Вы приняли должность мэра." : "Предложение отклонено.";
                    }, Route.PROFILE);
        } else if (action.startsWith("mayor_transfer_cancel:")) {
            String id = action.substring("mayor_transfer_cancel:".length());
            confirm(player, "Отменить передачу должности", "Кандидат больше не сможет принять это предложение.", "Отменить передачу",
                    () -> { cities.cancelMayorTransfer(player.getUniqueId(), id); return "Предложение передачи должности отменено."; }, Route.PROFILE);
        } else if (action.equals("mayor_resign_prompt")) {
            prompts.begin(player, "Укажите причину административной отставки (5–180 символов):", reason ->
                    execute(player, () -> { cities.submitMayorResignation(player.getUniqueId(), reason); return "Заявление об отставке отправлено администрации."; }, Route.PROFILE));
        } else if (action.startsWith("mayor_resignation_cancel:")) {
            String id = action.substring("mayor_resignation_cancel:".length());
            confirm(player, "Отозвать заявление об отставке", "Администрация больше не сможет одобрить это заявление.",
                    "Отозвать заявление", () -> {
                        cities.cancelMayorResignation(player.getUniqueId(), id);
                        return "Заявление об отставке отозвано.";
                    }, Route.PROFILE);
        } else if (action.startsWith("mayor_resignation_approve:") || action.startsWith("mayor_resignation_reject:")) {
            boolean approve = action.startsWith("mayor_resignation_approve:"); String id = action.substring(action.indexOf(':') + 1);
            confirmPermission(player, approve ? "Одобрить отставку" : "Отклонить отставку",
                    approve ? "Город останется без мэра до отдельного назначения." : "Должность мэра сохранится.",
                    approve ? "Одобрить" : "Отклонить", () -> {
                        CityService.MayorResignation value = cities.decideMayorResignation(player.getUniqueId(), id, approve);
                        if (approve) roleMirror.sync(value.mayorId(), CityRole.CITIZEN);
                        return approve ? "Отставка одобрена. Город добавлен в список вакансий." : "Отставка отклонена.";
                    }, Route.MAYOR_RESIGNATIONS_ADMIN, "citycore.admin.cities");
        } else if (action.startsWith("mayor_vacancy:")) {
            selectedVacantCity.put(player.getUniqueId(), action.substring("mayor_vacancy:".length()));
            rememberReturn(player, Route.MAYOR_APPOINT_ADMIN, current); navigate(player, Route.MAYOR_APPOINT_ADMIN);
        } else if (action.startsWith("mayor_appoint:")) {
            UUID candidate = uuid(action.substring("mayor_appoint:".length())); String cityId = selectedVacantCity.get(player.getUniqueId());
            if (candidate == null || cityId == null) return;
            confirmPermission(player, "Назначить мэра", "Город снова получит действующего мэра. Решение сохранится в истории.",
                    "Назначить", () -> {
                        cities.appointMayor(player.getUniqueId(), cityId, candidate); roleMirror.sync(candidate, CityRole.MAYOR);
                        return "Новый мэр назначен.";
                    }, Route.MAYOR_VACANCIES_ADMIN, "citycore.admin.cities");
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
        } else if (action.startsWith("business_withdraw:")) {
            String id = action.substring("business_withdraw:".length());
            confirm(player, "Отозвать регистрацию", "Предприятие не будет активировано и не получит счёт.", "Отозвать",
                    () -> { businesses.withdrawRegistration(player.getUniqueId(), id); return "Регистрация предприятия отозвана."; }, Route.BUSINESS);
        } else if (action.startsWith("business_oil_activity:")) {
            String id = action.substring("business_oil_activity:".length());
            confirm(player, "Выбрать нефтедобычу", "После создания промышленного объекта деятельность нельзя будет изменить напрямую.",
                    "Выбрать отрасль", () -> { businesses.setActivity(player.getUniqueId(), id, "OIL_EXTRACTION"); return "Предприятие переведено в нефтяную отрасль."; }, Route.BUSINESS_DETAIL);
        } else if (action.startsWith("license_choose:")) {
            selectedBusiness.put(player.getUniqueId(), action.substring("license_choose:".length()));
            rememberReturn(player, Route.LICENSE_TYPE, current); navigate(player, Route.LICENSE_TYPE);
        } else if (action.startsWith("license_type:")) {
            selectedLicenseType.put(player.getUniqueId(), action.substring("license_type:".length()));
            rememberReturn(player, Route.LICENSE_DURATION, current); navigate(player, Route.LICENSE_DURATION);
        } else if (action.startsWith("license_days:")) {
            String raw = action.substring("license_days:".length());
            if ("custom".equals(raw)) {
                int minimum = minimumLicenseDays(selectedLicenseType.getOrDefault(player.getUniqueId(), "TRADE"));
                prompts.beginValidated(player, "Введите срок лицензии целым числом от " + minimum + " до 3650 дней:", value -> {
                    try { int days = Integer.parseInt(value.strip()); if (days < minimum || days > 3650) throw new IllegalArgumentException(); return days; }
                    catch (RuntimeException invalid) { throw new IllegalArgumentException("Срок лицензии: целое число от " + minimum + " до 3650 дней."); }
                }, days -> issueSelectedLicense(player, days));
            } else {
                try { confirmSelectedLicense(player, Integer.parseInt(raw)); }
                catch (NumberFormatException ignored) { UiText.error(player, "Некорректный срок лицензии."); }
            }
        } else if (action.startsWith("license_revoke:")) {
            String[] parts = action.split(":", 3);
            if (parts.length != 3) return;
            confirm(player, "Отозвать лицензию " + parts[2], "Новые операции этого типа будут запрещены.",
                    "Отозвать лицензию", () -> {
                        businesses.revokeLicense(player.getUniqueId(), parts[1], parts[2]);
                        return "Лицензия отозвана.";
                    }, Route.BUSINESS_DETAIL);
        } else if (action.startsWith("industry_business:")) {
            selectedBusiness.put(player.getUniqueId(), action.substring("industry_business:".length()));
            rememberReturn(player, Route.INDUSTRY_BUSINESS, current); navigate(player, Route.INDUSTRY_BUSINESS);
        } else if (action.startsWith("controller_issue:")) {
            issueController(player, action.substring("controller_issue:".length()));
        } else if (action.startsWith("industry_controller:")) {
            selectedController.put(player.getUniqueId(), action.substring("industry_controller:".length()));
            rememberReturn(player, Route.INDUSTRY_CONTROLLER, current); navigate(player, Route.INDUSTRY_CONTROLLER);
        } else if (action.startsWith("controller_rebind:")) {
            String serial = action.substring("controller_rebind:".length());
            boolean industryAdministrator = player.hasPermission("citycore.admin.industry");
            confirm(player, "Восстановить контроллер", "Связанный объект вернётся в состояние паузы и потребует ручного запуска.",
                    "Восстановить", () -> {
                        industry.restoreControllerBinding(player.getUniqueId(), serial,
                                industryAdministrator);
                        return "Привязка контроллера восстановлена.";
                    }, Route.INDUSTRY_CONTROLLER);
        } else if (action.startsWith("industry_apply:")) {
            String depositId = action.substring("industry_apply:".length()); String businessId = selectedBusiness.get(player.getUniqueId()); String serial = selectedController.get(player.getUniqueId());
            if (businessId == null || serial == null) return;
            confirm(player, "Создать черновик объекта", "Контроллер и ресурсный участок будут связаны. Отправка инспектору выполняется отдельной кнопкой.",
                    "Создать черновик", () -> {
                        IndustryService.ObjectView object = industry.applyForObject(player.getUniqueId(), businessId, serial, depositId);
                        selectedObject.put(player.getUniqueId(), object.id()); return "Черновик нефтяного объекта создан. Проверьте карточку и подайте его на осмотр.";
                    }, Route.INDUSTRY_OBJECT);
        } else if (action.startsWith("industry_submit:")) {
            String id = action.substring("industry_submit:".length());
            confirm(player, "Подать объект на осмотр", "Инспектор проверит контроллер и участок, затем назначит уровень I–III.",
                    "Подать на осмотр", () -> { industry.submitObject(player.getUniqueId(), id); return "Нефтяной объект отправлен инспектору."; }, Route.INDUSTRY_OBJECT);
        } else if (action.startsWith("industry_object:")) {
            selectedObject.put(player.getUniqueId(), action.substring("industry_object:".length()));
            rememberReturn(player, Route.INDUSTRY_OBJECT, current); navigate(player, Route.INDUSTRY_OBJECT);
        } else if (action.startsWith("industry_withdraw:")) {
            String id = action.substring("industry_withdraw:".length());
            confirm(player, "Отозвать объект", "Заявка будет закрыта, контроллер останется размещённым.", "Отозвать",
                    () -> { industry.withdrawObjectApplication(player.getUniqueId(), id); return "Заявка на осмотр отозвана."; }, Route.INDUSTRY_BUSINESS);
        } else if (action.startsWith("industry_resubmit:")) {
            String id = action.substring("industry_resubmit:".length());
            confirm(player, "Повторная проверка", "Объект снова появится в очереди инспектора.", "Подать повторно",
                    () -> { industry.resubmitObject(player.getUniqueId(), id); return "Объект повторно отправлен на проверку."; }, Route.INDUSTRY_OBJECT);
        } else if (action.startsWith("industry_start:")) {
            String id = action.substring("industry_start:".length());
            confirm(player, "Запустить производство", "CityCore проверит лицензию, контроллер, долг, участок и запас.", "Запустить",
                    () -> { industry.start(player.getUniqueId(), id); return "Нефтяной объект запущен."; }, Route.INDUSTRY_OBJECT);
        } else if (action.startsWith("industry_pause:")) {
            String id = action.substring("industry_pause:".length());
            confirm(player, "Остановить производство", "Новая нефть не добывается, но обязательные платежи продолжают учитываться.", "Остановить",
                    () -> { industry.pause(player.getUniqueId(), id); return "Производство остановлено владельцем."; }, Route.INDUSTRY_OBJECT);
        } else if (action.startsWith("industry_decommission:")) {
            String id = action.substring("industry_decommission:".length());
            confirm(player, "Вывести из эксплуатации", "Действие необратимо. История и задолженность сохранятся.", "Вывести объект",
                    () -> { industry.decommission(player.getUniqueId(), id); return "Объект выведен из эксплуатации."; }, Route.INDUSTRY_BUSINESS);
        } else if (action.startsWith("industry_inspect:") || action.startsWith("industry_inspect_reject:")) {
            boolean approve = action.startsWith("industry_inspect:");
            String[] parts = action.split(":"); String id = parts.length > 1 ? parts[1] : ""; int level = approve && parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            prompts.begin(player, approve ? "Введите комментарий инспектора или '-' без замечаний:" : "Укажите причину отклонения:", note -> {
                String normalized = "-".equals(note) ? "Без замечаний" : note;
                boolean industryAdministrator = player.hasPermission("citycore.admin.industry");
                confirm(player, approve ? "Одобрить объект уровня " + roman(level) : "Отклонить объект",
                        normalized, approve ? "Назначить уровень" : "Отклонить", () -> {
                            industry.inspect(player.getUniqueId(), id, approve, level, normalized,
                                    industryAdministrator);
                            return approve ? "Объект прошёл инспекцию. Владелец должен получить лицензию и запустить его." : "Объект отклонён.";
                        }, Route.INDUSTRY_INSPECTIONS);
            });
        } else if (action.equals("deposit_create")) {
            if (!player.hasPermission("citycore.admin.industry")) { UiText.error(player, "Нет права создавать месторождения."); return; }
            var location = player.getLocation();
            prompts.begin(player, "Введите ID города для месторождения:", citySlug ->
                    prompts.begin(player, "Введите системный ID месторождения:", slug ->
                            prompts.begin(player, "Введите название месторождения:", name ->
                                    prompts.beginValidated(player, "Введите радиус участка от 1 до 1024:", value -> positiveInt(value, 1, 1024, "Радиус"), radius ->
                                            prompts.beginValidated(player, "Введите общий запас нефти целым числом:", value -> positiveLong(value, "Запас"), reserve ->
                                                    prompts.beginValidated(player, "Введите общий лимит добычи за период:", value -> positiveLong(value, "Лимит"), throughput ->
                                                            execute(player, () -> {
                                                                industry.createDeposit(player.getUniqueId(), citySlug, slug, name,
                                                                        location.getWorld().getUID(), location.getWorld().getName(),
                                                                        location.getBlockX(), location.getBlockY(), location.getBlockZ(), radius, reserve, throughput);
                                                                return "Месторождение «" + name + "» создано в текущей точке.";
                                                            }, Route.INDUSTRY_DEPOSITS)))))));
        } else if (action.equals("procurement_fund")) {
            prompts.beginValidated(player, "Введите сумму перевода из казны в закупочный фонд:",
                    value -> positiveMoney(value), amount -> confirm(player, "Пополнить закупочный фонд",
                            "Из казны будет переведено: " + formatMinor(amount), "Перевести",
                            () -> { industry.fundProcurement(player.getUniqueId(), amount); return "Закупочный фонд пополнен на " + formatMinor(amount) + "."; }, Route.INDUSTRY_POLICY));
        } else if (action.startsWith("industry_tax:")) {
            int bps;
            try { bps = Integer.parseInt(action.substring("industry_tax:".length())); } catch (NumberFormatException invalid) { return; }
            confirm(player, "Изменить промышленный налог", "Новая ставка " + percent(bps) + " применяется только к будущим циклам.",
                    "Установить ставку", () -> { industry.setTax(player.getUniqueId(), bps); return "Промышленный налог установлен: " + percent(bps) + "."; }, Route.INDUSTRY_POLICY);
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
        selectedController.remove(playerId);
        selectedObject.remove(playerId);
        selectedLicenseType.remove(playerId);
        selectedVacantCity.remove(playerId);
        confirmations.remove(playerId);
        returnRoutes.remove(playerId);
    }

    private void issueController(Player player, String businessId) {
        player.closeInventory();
        storage.submit(() -> industry.issueController(player.getUniqueId(), businessId)).whenComplete((controller, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    if (error != null) { failure(player, error); return; }
                    ItemStack item = controllerItems.create(plugin.config().industry().controllerMaterial(),
                            controller.serial(), controller.businessName());
                    Map<Integer, ItemStack> remaining = player.getInventory().addItem(item);
                    remaining.values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
                    feedback.success(player);
                    UiText.success(player, "Выдан контроллер " + controller.serial() + ". Установите его как блок.");
                    navigate(player, Route.INDUSTRY_BUSINESS);
                }));
    }

    private void confirmSelectedLicense(Player player, int days) {
        String businessId = selectedBusiness.get(player.getUniqueId());
        String type = selectedLicenseType.get(player.getUniqueId());
        if (businessId == null || type == null) { UiText.error(player, "Предприятие или тип лицензии больше не выбраны."); return; }
        confirm(player, "Выдать лицензию " + type, "Срок действия: " + days + " дней. Предприятие и полномочия проверятся повторно.",
                "Выдать лицензию", () -> "Лицензия выдана: " + businesses.issueLicense(player.getUniqueId(), businessId, type, days).type() + ".",
                Route.BUSINESS_DETAIL);
    }

    private void issueSelectedLicense(Player player, int days) { confirmSelectedLicense(player, days); }

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
            case LICENSE_TYPE -> Route.BUSINESS_DETAIL;
            case LICENSE_DURATION -> Route.LICENSE_TYPE;
            case INDUSTRY_BUSINESS -> Route.BUSINESS_DETAIL;
            case INDUSTRY_CONTROLLER, INDUSTRY_OBJECT -> Route.INDUSTRY_BUSINESS;
            case INDUSTRY_DEPOSIT_PICK -> Route.INDUSTRY_CONTROLLER;
            case INDUSTRY_INSPECTIONS -> Route.GOVERNMENT;
            case INDUSTRY_POLICY -> Route.MAYOR;
            case INDUSTRY_DEPOSITS, MAYOR_RESIGNATIONS_ADMIN, MAYOR_VACANCIES_ADMIN -> Route.ADMIN;
            case MAYOR_APPOINT_ADMIN -> Route.MAYOR_VACANCIES_ADMIN;
            case MAYOR_TRANSFER -> Route.PROFILE;
            case MAYOR_TRANSFER_INBOX -> Route.PROFILE;
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

    private long positiveMoney(String value) {
        long result;
        try { result = MinorUnits.parse(value, plugin.config().currencyScale()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Введите положительную сумму, например 100.00"); }
        if (result <= 0) throw new IllegalArgumentException("Сумма должна быть положительной.");
        return result;
    }

    private int positiveInt(String value, int minimum, int maximum, String label) {
        try {
            int result = Integer.parseInt(value.strip());
            if (result < minimum || result > maximum) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(label + ": целое число от " + minimum + " до " + maximum + ".");
        }
    }

    private long positiveLong(String value, String label) {
        try {
            long result = Long.parseLong(value.strip());
            if (result <= 0) throw new IllegalArgumentException();
            return result;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException(label + ": положительное целое число.");
        }
    }

    private String date(Instant instant) {
        return instant == null ? "без срока" : DATE.format(instant);
    }

    private String dateTime(Instant instant) {
        return instant == null ? "не назначен" : DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                .withZone(ZoneId.systemDefault()).format(instant);
    }

    private String roman(int level) { return switch (level) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; default -> Integer.toString(level); }; }
    private int minimumLicenseDays(String type) { return switch (type) {
        case "OIL_EXTRACTION_I" -> plugin.config().industry().level(1).minimumLicenseDays();
        case "OIL_EXTRACTION_II" -> plugin.config().industry().level(2).minimumLicenseDays();
        case "OIL_EXTRACTION_III" -> plugin.config().industry().level(3).minimumLicenseDays();
        default -> 1;
    }; }
    private String percent(int basisPoints) { return String.format(Locale.ROOT, "%.2f%%", basisPoints / 100.0); }
    private String activityLabel(String activity) { return switch (activity) {
        case "GENERAL" -> "общая"; case "OIL_EXTRACTION" -> "нефтедобыча"; default -> activity.toLowerCase(Locale.ROOT);
    }; }

    private Material statusMaterial(String status) {
        return switch (status) {
            case "ACTIVE", "ACCEPTED", "APPROVED", "COMPLETED" -> Material.LIME_CONCRETE;
            case "PENDING", "WARNING", "EXPIRING" -> Material.YELLOW_CONCRETE;
            case "DRAFT" -> Material.LIGHT_BLUE_CONCRETE;
            case "REJECTED", "REVOKED", "BLOCKED", "CLOSED", "FAILED" -> Material.RED_CONCRETE;
            case "DEBT", "SUSPENDED", "CLOSING", "EXPIRED" -> Material.ORANGE_CONCRETE;
            case "PAUSED", "MISSING_CONTROLLER", "SUSPENDED_LICENSE", "WAITING_BUYER_FUNDS", "SUSPENDED_DEBT" -> Material.ORANGE_CONCRETE;
            case "DEPLETED", "DECOMMISSIONED", "CANCELLED", "RETIRED" -> Material.GRAY_CONCRETE;
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
            case "PENDING_INSPECTION" -> "ожидает осмотра";
            case "DRAFT" -> "черновик";
            case "PAUSED" -> "остановлено владельцем";
            case "MISSING_CONTROLLER" -> "нет контроллера";
            case "SUSPENDED_LICENSE" -> "нет действующей лицензии";
            case "WAITING_BUYER_FUNDS" -> "закупочный фонд пуст";
            case "SUSPENDED_DEBT" -> "остановлено из-за долга";
            case "DEPLETED" -> "месторождение исчерпано";
            case "DECOMMISSIONED" -> "выведено из эксплуатации";
            case "CANCELLED" -> "отозвано";
            case "ISSUED" -> "выдан, не размещён";
            case "PLACED" -> "размещён";
            case "BOUND" -> "привязан к объекту";
            case "RETIRED" -> "выведен из эксплуатации";
            case "MAYOR_VACANCY" -> "требуется назначение мэра";
            default -> status.toLowerCase(Locale.ROOT);
        };
    }

    private String title(Player player, Route route) {
        return switch (route) {
            case HOME -> "CityCore · Панель";
            case PROFILE -> "CityCore · Личное";
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
            case MAYOR_TRANSFER -> "CityCore · Передача должности";
            case MAYOR_TRANSFER_INBOX -> "CityCore · Предложение мэра";
            case MAYOR_RESIGNATIONS_ADMIN -> "CityCore · Отставки мэров";
            case MAYOR_VACANCIES_ADMIN -> "CityCore · Вакансии мэров";
            case MAYOR_APPOINT_ADMIN -> "CityCore · Назначение мэра";
            case LICENSE_TYPE -> "CityCore · Тип лицензии";
            case LICENSE_DURATION -> "CityCore · Срок лицензии";
            case INDUSTRY_BUSINESS -> "CityCore · Нефтяная отрасль";
            case INDUSTRY_CONTROLLER -> "CityCore · Контроллер";
            case INDUSTRY_DEPOSIT_PICK -> "CityCore · Выбор месторождения";
            case INDUSTRY_OBJECT -> "CityCore · Нефтяной объект";
            case INDUSTRY_INSPECTIONS -> "CityCore · Промышленные осмотры";
            case INDUSTRY_DEPOSITS -> "CityCore · Месторождения";
            case INDUSTRY_POLICY -> "CityCore · Промышленная политика";
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
    private record PersonalData(CityService.CityView city, List<BusinessService.BusinessView> businesses,
                                List<CityService.PlayerApplication> applications,
                                CityService.FoundationApplication foundation,
                                List<CityService.MayorTransfer> transfers,
                                List<CityService.MayorTransfer> outgoingTransfers,
                                CityService.MayorResignation mayorResignation) {}
    private record CityScreenData(CityService.CityView city, List<CityService.PlayerApplication> applications,
                                  CityService.FoundationApplication foundation) {}
    private record MemberScreenData(CityService.CityView city, List<CityService.Member> members) {}
    private record BusinessScreenData(CityService.CityView city, List<BusinessService.BusinessView> items) {}
    private record EconomyScreenData(CityService.CityView city, List<BusinessService.BusinessView> businesses) {}
    private record EmissionScreenData(List<CityService.CitySummary> cities, List<EmissionService.Issue> issues) {}
    private record IndustryBusinessData(BusinessService.BusinessDetail business,
                                        List<IndustryService.ObjectView> objects,
                                        List<IndustryService.ControllerView> controllers) {}
    private record IndustryObjectData(IndustryService.ObjectView object, List<IndustryService.CycleView> cycles) {}
    private record AdminIndustryData(List<IndustryService.DepositView> deposits,
                                     List<IndustryService.ObjectView> objects) {}
}
