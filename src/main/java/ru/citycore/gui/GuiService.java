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
import ru.citycore.business.BusinessActivity;
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
import ru.citycore.communication.CallSession;
import ru.citycore.communication.CallState;
import ru.citycore.communication.CommunicationService;
import ru.citycore.communication.Device;
import ru.citycore.communication.DeviceType;

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
    private final CommunicationService communication;
    private final CapabilityService capabilities = new CapabilityService();
    private final GuiIconRegistry icons;
    private final NamespacedKey actionKey;
    private final Map<UUID, String> selectedBusiness = new ConcurrentHashMap<>();
    private final Map<UUID, CityService.Application> selectedApplication = new ConcurrentHashMap<>();
    private final Map<UUID, CityService.FoundationApplication> selectedFoundation = new ConcurrentHashMap<>();
    private final Map<UUID, Confirmation> confirmations = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedController = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedObject = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedLicenseType = new ConcurrentHashMap<>();
    private final Map<UUID, String> selectedVacantCity = new ConcurrentHashMap<>();
    private final Map<UUID, BusinessDraft> businessDrafts = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Route, Route>> returnRoutes = new ConcurrentHashMap<>();
    private final Map<UUID, Route> workspaceRoots = new ConcurrentHashMap<>();

    public GuiService(CityCorePlugin plugin, EconomyGateway economy, StorageExecutor storage, CityService cities,
                      BusinessService businesses, ChatPromptService prompts, GuiFeedback feedback,
                      VaultTransferCoordinator vaultTransfers, EmissionService emission, RoleMirror roleMirror) {
        this(plugin, economy, storage, cities, businesses, prompts, feedback, vaultTransfers, emission,
                roleMirror, null, null, null);
    }

    public GuiService(CityCorePlugin plugin, EconomyGateway economy, StorageExecutor storage, CityService cities,
                      BusinessService businesses, ChatPromptService prompts, GuiFeedback feedback,
                      VaultTransferCoordinator vaultTransfers, EmissionService emission, RoleMirror roleMirror,
                      IndustryService industry, ControllerItems controllerItems, CommunicationService communication) {
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
        this.communication = communication;
        this.actionKey = new NamespacedKey(plugin, "gui_action");
        this.icons = new GuiIconRegistry(() -> plugin.config().customHeads());
    }

    public void open(Player player, Route route) {
        if (route == Route.HOME || route == Route.MAYOR || route == Route.GOVERNMENT || route == Route.ADMIN) {
            workspaceRoots.put(player.getUniqueId(), route);
        } else {
            workspaceRoots.putIfAbsent(player.getUniqueId(), Route.HOME);
        }
        open(player, route, route == Route.HOME);
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
        Route requestedRoute = route;
        if (route == Route.COMMUNICATIONS) {
            UiText.info(player, "Телефон и рация открываются отдельными устройствами.");
            route = Route.HOME;
        }
        if (route == Route.PHONE_DEVICE && (communication == null
                || communication.devices().active(player.getUniqueId(), DeviceType.PHONE).isEmpty())) {
            UiText.error(player, "Активный телефон не найден. Перезайдите или обратитесь к администратору.");
            return;
        }
        if (route == Route.RADIO_CHANNELS && (communication == null
                || communication.devices().active(player.getUniqueId(), DeviceType.RADIO).isEmpty())) {
            UiText.error(player, "Активная рация не найдена. Перезайдите или обратитесь к администратору.");
            return;
        }
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
        if (route == Route.INDUSTRY_DEPOSITS || route == Route.INDUSTRY_DEPOSIT_PICK) {
            UiText.info(player, "Ручные месторождения убраны. Автоматические нефтяные участки появятся в следующей версии.");
            route = route == Route.INDUSTRY_DEPOSIT_PICK ? Route.INDUSTRY_CONTROLLER : Route.ADMIN;
        }
        if ((route == Route.MAYOR_RESIGNATIONS_ADMIN || route == Route.MAYOR_VACANCIES_ADMIN
                || route == Route.MAYOR_APPOINT_ADMIN) && !player.hasPermission("citycore.admin.cities")) {
            UiText.error(player, "У вас нет доступа к управлению городскими должностями."); route = Route.HOME;
        }
        if (route == Route.HOME && requestedRoute != Route.HOME) {
            workspaceRoots.put(player.getUniqueId(), Route.HOME);
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
        decorate(inventory, route);
        switch (route) {
            case HOME -> renderHome(player, inventory);
            case PROFILE -> renderProfile(player, inventory);
            case CITY -> renderCity(player, inventory);
            case CITY_STATS -> renderCityStats(player, inventory);
            case CITY_DIRECTORY -> renderCityDirectory(player, inventory);
            case CITY_APPLICATIONS -> renderCityApplications(player, inventory);
            case CITY_APPLICATION_DETAIL -> renderCityApplication(player, inventory);
            case CITY_MEMBERS -> renderCityMembers(player, inventory);
            case BUSINESS -> renderBusinesses(player, inventory, false);
            case BUSINESS_ACTIVITY -> renderBusinessActivities(player, inventory);
            case BUSINESS_OIL_LEVEL -> renderOilBusinessLevel(player, inventory);
            case BUSINESS_PENDING -> renderBusinesses(player, inventory, true);
            case BUSINESS_DETAIL -> renderBusinessDetail(player, inventory);
            case LICENSES -> renderLicenses(player, inventory);
            case ECONOMY -> renderEconomy(player, inventory);
            case COMMUNICATIONS -> renderCommunications(player, inventory);
            case PHONE_DEVICE -> renderPhoneDevice(player, inventory);
            case RADIO_CHANNELS -> renderRadioChannels(player, inventory);
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
            case CONFIRM -> renderConfirmation(player, inventory);
        }
        renderNavigation(player, route, inventory);
    }

    private void renderHome(Player player, Inventory inventory) {
        int[] slots = GuiLayout.homeCategorySlots();
        inventory.setItem(slots[0], playerHead(player.getUniqueId(), "Личное", "route:PROFILE",
                "Профиль, кошелёк и личный статус"));
        inventory.setItem(slots[1], button(GuiIcon.CITY, "Город", "route:CITY",
                "Городская информация и статистика"));
        inventory.setItem(slots[2], button(GuiIcon.BUSINESS, "Предприятия", "route:BUSINESS",
                "Создание и управление своими компаниями"));
        boolean reviewFoundations = capabilities.allows(player, null, Capability.REVIEW_CITY_FOUNDATIONS);
        boolean manageCities = player.hasPermission("citycore.admin.cities");
        boolean emissionAvailable = capabilities.allows(player, null, Capability.ISSUE_CURRENCY)
                && plugin.config().emissionEnabled();
        storage.submit(() -> new HomeScreenData(cities.view(player.getUniqueId()),
                        reviewFoundations && !cities.pendingFoundations().isEmpty(),
                        manageCities && (!cities.pendingMayorResignations().isEmpty() || !cities.vacantCities().isEmpty()),
                        emissionAvailable, businesses.pendingReviewCount(player.getUniqueId())))
                .whenComplete((data, error) ->
                sync(player, inventory, () -> {
                    if (error != null) return;
                    List<ItemStack> workspaces = new ArrayList<>();
                    CityService.CityView city = data.city();
                    if (city != null && city.role() == CityRole.MAYOR) {
                        workspaces.add(button(GuiIcon.MAYOR,
                                data.businessReviews() > 0 ? "Мэрия · " + data.businessReviews() + " задач" : "Мэрия",
                                "route:MAYOR", "Полномочия главы города",
                                data.businessReviews() > 0 ? "Новые регистрации предприятий ждут решения" : "Новых регистраций нет"));
                    } else if (city != null && city.role() == CityRole.OFFICIAL) {
                        workspaces.add(button(GuiIcon.GOVERNMENT,
                                data.businessReviews() > 0 ? "Госслужба · " + data.businessReviews() + " задач" : "Государственная служба",
                                "route:GOVERNMENT", "Рабочие реестры чиновника",
                                data.businessReviews() > 0 ? "Новые регистрации предприятий ждут решения" : "Новых регистраций нет"));
                    }
                    if (data.adminWorkAvailable()) {
                        workspaces.add(button(GuiIcon.ADMIN, "Администрирование", "route:ADMIN",
                                "Доступные операции проекта"));
                    }
                    putCentered(inventory, 4, workspaces);
                }));
    }

    private void renderProfile(Player player, Inventory inventory) {
        inventory.setItem(20, playerHead(player.getUniqueId(), player.getName(), null,
                "Личный профиль CityCore"));
        inventory.setItem(22, info(GuiIcon.WALLET, "Личный кошелёк",
                formatMinor(economy.balanceMinor(player.getUniqueId())), "Источник: EssentialsX Economy"));
        inventory.setItem(24, loading("Загрузка личного статуса…"));
        storage.submit(() -> new ProfileScreenData(cities.view(player.getUniqueId()),
                        cities.incomingMayorTransfers(player.getUniqueId())))
                .whenComplete((data, error) ->
                sync(player, inventory, () -> {
                    inventory.setItem(24, null);
                    if (error != null) { inventory.setItem(24, errorItem(error)); return; }
                    CityService.CityView city = data.city();
                    List<ItemStack> actions = new ArrayList<>();
                    if (city == null) {
                        inventory.setItem(24, info(GuiIcon.ROLE, "Без гражданства",
                                "Выбрать город можно в городском разделе"));
                    } else {
                        inventory.setItem(24, info(GuiIcon.ROLE, roleLabel(city.role()),
                                "Город: " + city.name()));
                        if (city.role() == CityRole.CITIZEN) {
                            actions.add(button(GuiIcon.LEAVE, "Покинуть город", "city_leave",
                                    "Завершить гражданство", "Предприятия и долги сохранятся"));
                        } else if (city.role() == CityRole.OFFICIAL) {
                            actions.add(button(GuiIcon.RESIGN, "Сложить полномочия", "official_resign",
                                    "Вернуться к статусу обычного гражданина"));
                        }
                    }
                    if (!data.incomingTransfers().isEmpty()) {
                        actions.add(button(GuiIcon.MAYOR, "Предложение стать мэром", "route:MAYOR_TRANSFER_INBOX",
                                "Принять или отклонить передачу должности"));
                    }
                    putCentered(inventory, 3, actions);
                }));
    }

    private void renderCity(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Загрузка городского профиля…"));
        storage.submit(() -> new CityScreenData(cities.view(player.getUniqueId()),
                        cities.applications(player.getUniqueId()), cities.latestFoundation(player.getUniqueId())))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    if (error != null) {
                        inventory.setItem(22, errorItem(error));
                        return;
                    }
                    clearList(inventory);
                    if (data.city() == null) {
                        inventory.setItem(13, info(GuiIcon.CITY, "Город не выбран",
                                "Гражданство оформляется через заявление", "Одновременно доступен один город"));
                        inventory.setItem(21, button(GuiIcon.DIRECTORY, "Каталог городов", "route:CITY_DIRECTORY",
                                "Выбрать активный город и подать заявление"));
                        renderFoundationShortcutAt(inventory, 23, null, data.foundation());
                        List<ItemStack> applications = new ArrayList<>();
                        for (CityService.PlayerApplication application : data.applications()) {
                            if (!"PENDING".equals(application.status()) || applications.size() >= 5) continue;
                            applications.add(button(GuiIcon.APPLICATION, "Отозвать · " + application.cityName(),
                                    "citizenship_cancel:" + application.id(), "Заявление ожидает решения",
                                    "Подано: " + date(application.createdAt())));
                        }
                        if (data.foundation() != null && "PENDING".equals(data.foundation().status())) {
                            applications.add(button(GuiIcon.CANCEL, "Отозвать основание · " + data.foundation().name(),
                                    "foundation_cancel:" + data.foundation().id(), "Город создан не будет"));
                        }
                        putCentered(inventory, 3, applications);
                        return;
                    }
                    CityService.CityView city = data.city();
                    inventory.setItem(13, info(GuiIcon.CITY, city.name(),
                            "Состояние: " + statusLabel(city.status()), "Городской код: " + city.slug()));
                    putCentered(inventory, 2, List.of(
                            button(GuiIcon.STATISTICS, "Статистика", "route:CITY_STATS",
                                    "Население, предприятия и промышленность"),
                            button(GuiIcon.RESIDENTS, "Жители", "route:CITY_MEMBERS",
                                    "Публичный состав города"),
                            button(GuiIcon.DIRECTORY, "Другие города", "route:CITY_DIRECTORY",
                                    "Открыть городской каталог")));
                }));
    }

    private void renderCityStats(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Собираем городскую статистику…"));
        storage.submit(() -> new CityStatsScreenData(cities.view(player.getUniqueId()),
                        cities.stats(player.getUniqueId())))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    clearList(inventory);
                    if (error != null) { inventory.setItem(22, errorItem(error)); return; }
                    CityService.CityStats stats = data.stats();
                    inventory.setItem(13, info(GuiIcon.STATISTICS, "Статистика · " + data.city().name(),
                            "Только фактически сохранённые данные"));
                    List<ItemStack> facts = new ArrayList<>();
                    facts.add(info(GuiIcon.RESIDENTS, "Население: " + stats.population(),
                            "Граждане: " + stats.citizens(), "Чиновники: " + stats.officials(),
                            "Мэры: " + stats.mayors()));
                    facts.add(info(GuiIcon.BUSINESS, "Активные предприятия: " + stats.activeBusinesses(),
                            "Ожидают регистрации: " + stats.pendingBusinesses()));
                    facts.add(info(GuiIcon.INDUSTRY, "Промышленные объекты: " + stats.industrialObjects(),
                            "Учтены все незакрытые объекты"));
                    if (data.city().role() == CityRole.MAYOR || data.city().role() == CityRole.OFFICIAL) {
                        facts.add(info(GuiIcon.TREASURY, "Городская казна",
                                formatMinor(data.city().treasuryMinor()), "Доступно по служебной должности"));
                    }
                    putCentered(inventory, 2, facts);
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
                inventory.setItem(22, button(GuiIcon.FOUNDATION, "Основать первый город",
                        "prompt:city_foundation", "Отправить заявку администрации"));
                return;
            }
            List<ItemStack> cards = new ArrayList<>();
            for (int i = 0; i < items.size() && i < LIST_SLOTS.length; i++) {
                CityService.CitySummary city = items.get(i);
                cards.add(button(GuiIcon.CITY, city.name(), "city_apply:" + city.slug(),
                        "Жителей: " + city.citizenCount(), "Подать заявление на гражданство"));
            }
            putCenteredGrid(inventory, 1, 4, cards);
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
                inventory.setItem(22, info(GuiIcon.EMPTY, "Очередь пуста",
                        "Новых заявлений на гражданство нет"));
                return;
            }
            List<ItemStack> cards = new ArrayList<>();
            for (int i = 0; i < items.size() && i < LIST_SLOTS.length; i++) {
                CityService.Application application = items.get(i);
                cards.add(playerHead(application.playerId(), application.playerName(),
                        "city_application:" + application.playerId(), "Заявление на гражданство",
                        "Подано: " + date(application.createdAt())));
            }
            putCenteredGrid(inventory, 1, 4, cards);
        }));
    }

    private void renderCityApplication(Player player, Inventory inventory) {
        CityService.Application application = selectedApplication.get(player.getUniqueId());
        if (application == null) {
            inventory.setItem(10, info(GuiIcon.ERROR, "Заявление не выбрано",
                    "Вернитесь к очереди и выберите игрока"));
            return;
        }
        inventory.setItem(10, playerHead(application.playerId(), application.playerName(), null,
                "Кандидат на гражданство", "Подано: " + date(application.createdAt())));
        inventory.setItem(19, button(GuiIcon.CONFIRM, "Принять заявление",
                "city_accept:" + application.playerId(), "Добавить игрока в город как жителя"));
        inventory.setItem(20, button(GuiIcon.CANCEL, "Отклонить заявление",
                "city_reject:" + application.playerId(), "Закрыть заявление без гражданства"));
        inventory.setItem(11, info(GuiIcon.DOCUMENTS, "Решение будет записано",
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
                    boolean mayor = data.city() != null && data.city().role() == CityRole.MAYOR
                            && workspaceRoots.getOrDefault(player.getUniqueId(), Route.HOME) == Route.MAYOR;
                    List<ItemStack> cards = new ArrayList<>();
                    for (int i = 0; i < data.members().size() && i < LIST_SLOTS.length; i++) {
                        CityService.Member member = data.members().get(i);
                        String action = null;
                        String hint = "Просмотр участника города";
                        if (mayor && member.role() != CityRole.MAYOR) {
                            CityRole next = member.role() == CityRole.OFFICIAL ? CityRole.CITIZEN : CityRole.OFFICIAL;
                            action = "city_role:" + member.playerId() + ":" + next.name();
                            hint = next == CityRole.OFFICIAL ? "Назначить городским чиновником" : "Снять должность чиновника";
                        }
                        cards.add(playerHead(member.playerId(), member.playerName(), action,
                                "Должность: " + roleLabel(member.role()), "В городе с " + date(member.joinedAt()), hint));
                    }
                    putCenteredGrid(inventory, 1, 4, cards);
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
                    boolean officialWorkspace = privilegedWorkspace(player);
                    List<BusinessService.BusinessView> visible = officialWorkspace ? data.items()
                            : data.items().stream().filter(item -> item.ownerId().equals(player.getUniqueId())).toList();
                    int limit = pendingOnly ? LIST_SLOTS.length : 21;
                    List<ItemStack> cards = new ArrayList<>();
                    for (int i = 0; i < visible.size() && i < limit; i++) {
                        BusinessService.BusinessView business = visible.get(i);
                        String ownership = business.ownerId().equals(player.getUniqueId()) ? "Владелец: вы" : "Предприятие вашего города";
                        cards.add(button(GuiIcon.BUSINESS, business.name(),
                                "business_view:" + business.id(), ownership,
                                "Направление: " + activityLabel(business.activityType()),
                                business.requestedIndustryLevel() == null ? "Состояние: " + statusLabel(business.status())
                                        : "Желаемый уровень вышки: " + roman(business.requestedIndustryLevel()),
                                "Состояние: " + statusLabel(business.status())));
                    }
                    putCenteredGrid(inventory, 1, 3, cards);
                    if (visible.isEmpty() && pendingOnly) inventory.setItem(22, info(GuiIcon.CONFIRM,
                            "Очередь обработана", "Новых регистраций сейчас нет"));
                    if (!pendingOnly) {
                        if (!officialWorkspace) {
                            ItemStack primary = data.city() == null
                                    ? button(GuiIcon.CITY, "Сначала выберите город", "route:CITY",
                                    "Регистрация предприятия требует городской юрисдикции")
                                    : button(GuiIcon.REGISTRATION, "Создать предприятие", "prompt:business_register",
                                    "Подать заявку на регистрацию в городе " + data.city().name());
                            putCentered(inventory, 4, List.of(primary));
                        }
                    }
                }));
    }

    private void renderBusinessActivities(Player player, Inventory inventory) {
        BusinessDraft draft = businessDrafts.get(player.getUniqueId());
        if (draft == null) {
            inventory.setItem(22, info(GuiIcon.ERROR, "Черновик регистрации не найден",
                    "Вернитесь в предприятия и начните создание заново"));
            return;
        }
        inventory.setItem(13, info(GuiIcon.REGISTRATION, "Направление · " + draft.name(),
                "Выберите фактический формат предприятия",
                "Направление будет видно проверяющему чиновнику"));
        List<ItemStack> activities = new ArrayList<>();
        activities.add(button(Material.BARREL, BusinessActivity.SMALL_RETAIL.displayName(),
                "business_activity:SMALL_RETAIL", "Ларёк, небольшой магазин или частная точка",
                "Активируется сразу, без служебного рассмотрения"));
        activities.add(button(Material.EMERALD, BusinessActivity.TRADE.displayName(),
                "business_activity:TRADE", "Полноценная торговая организация",
                "Заявка поступит городской службе"));
        activities.add(button(Material.PAPER, BusinessActivity.SERVICES.displayName(),
                "business_activity:SERVICES", "Работы и услуги для жителей или организаций",
                "Заявка поступит городской службе"));
        activities.add(button(Material.CRAFTING_TABLE, BusinessActivity.MANUFACTURING.displayName(),
                "business_activity:MANUFACTURING", "Производство товаров и материалов",
                "Заявка поступит городской службе"));
        activities.add(button(GuiIcon.OIL, BusinessActivity.OIL_EXTRACTION.displayName(),
                "business_activity:OIL_EXTRACTION", "Развитие предприятия через нефтяную вышку",
                "Потребуется короткий технический опросник"));
        putCentered(inventory, 2, activities);
    }

    private void renderOilBusinessLevel(Player player, Inventory inventory) {
        BusinessDraft draft = businessDrafts.get(player.getUniqueId());
        if (draft == null || draft.activity() != BusinessActivity.OIL_EXTRACTION) {
            inventory.setItem(22, info(GuiIcon.ERROR, "Нефтяной черновик не найден",
                    "Сначала выберите нефтедобычу как направление"));
            return;
        }
        inventory.setItem(13, info(GuiIcon.OIL, "Оценка будущей нефтевышки",
                "Это оценка предпринимателя, а не готовая лицензия",
                "Окончательный уровень подтвердит инспектор после осмотра",
                "Продажа нефти государству: фиксированное удержание 20%"));
        putCentered(inventory, 2, List.of(
                button(Material.COPPER_INGOT, "Уровень I · малая", "business_oil_level:1",
                        "Начальная установка и небольшой объём добычи"),
                button(Material.IRON_INGOT, "Уровень II · промышленная", "business_oil_level:2",
                        "Средняя производительность и требования"),
                button(Material.GOLD_INGOT, "Уровень III · крупная", "business_oil_level:3",
                        "Максимальная оценка оборудования и масштаба")
        ));
    }

    private void renderBusinessDetail(Player player, Inventory inventory) {
        String businessId = selectedBusiness.get(player.getUniqueId());
        if (businessId == null) {
            inventory.setItem(10, info(GuiIcon.ERROR, "Предприятие не выбрано",
                    "Вернитесь к списку и откройте карточку"));
            return;
        }
        boolean industryAdministrator = player.hasPermission("citycore.admin.industry");
        inventory.setItem(10, loading("Загрузка карточки…"));
        storage.submit(() -> {
                    BusinessService.BusinessDetail business = businesses.detail(player.getUniqueId(), businessId);
                    List<IndustryService.ObjectView> objects = industry != null && "OIL_EXTRACTION".equals(business.activityType())
                            ? industry.objects(player.getUniqueId(), businessId, industryAdministrator)
                            : List.of();
                    return new BusinessDetailScreenData(business, objects);
                })
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    clearList(inventory);
                    if (error != null) {
                        inventory.setItem(22, errorItem(error));
                        return;
                    }
                    BusinessService.BusinessDetail business = data.business();
                    inventory.setItem(13, info(GuiIcon.BUSINESS, business.name(),
                            "Состояние: " + statusLabel(business.status()),
                            "Деятельность: " + activityLabel(business.activityType()),
                            "OIL_EXTRACTION".equals(business.activityType())
                                    ? "Госзакупка нефти: фиксированное удержание 20%"
                                    : "Городская регистрационная карточка"));
                    inventory.setItem(11, playerHead(business.ownerId(), business.ownerName(), null,
                            "Владелец"));
                    if (business.balanceMinor() != null) inventory.setItem(15, info(GuiIcon.ACCOUNT,
                            "Счёт предприятия", formatMinor(business.balanceMinor()), "Доступен владельцу и городской власти"));
                    if (business.requestedIndustryLevel() != null || !business.applicationNote().isBlank()) {
                        inventory.setItem(16, info(GuiIcon.APPLICATION, "Заявка предпринимателя",
                                business.requestedIndustryLevel() == null ? "Уровень не заявлен"
                                        : "Желаемый уровень вышки: " + roman(business.requestedIndustryLevel()),
                                business.applicationNote().isBlank() ? "Дополнительного описания нет"
                                        : shorten(business.applicationNote(), 100),
                                "Окончательный уровень определяет осмотр"));
                    }

                    List<ItemStack> actions = new ArrayList<>();
                    if (business.owner() && business.accountId() != null) {
                        actions.add(button(GuiIcon.ACCOUNT, "Пополнить счёт",
                                "business_deposit:" + business.id(), "Перевести деньги из личного кошелька"));
                    }
                    if (business.owner() && "PENDING".equals(business.status())) {
                        actions.add(button(GuiIcon.CANCEL, "Отозвать регистрацию",
                                "business_withdraw:" + business.id(), "Закрыть ожидающее заявление"));
                    }
                    if (business.owner() && !data.objects().isEmpty()) {
                        actions.add(button(GuiIcon.INDUSTRY, "Нефтяные объекты",
                                "industry_business:" + business.id(), "Управление существующими установками"));
                    }
                    boolean officialActions = business.official() && privilegedWorkspace(player);
                    if (officialActions && "PENDING".equals(business.status())) {
                        actions.add(button(GuiIcon.CONFIRM, "Одобрить регистрацию",
                                "business_approve:" + business.id(), "Активировать предприятие и открыть счёт"));
                        actions.add(button(GuiIcon.CANCEL, "Отклонить регистрацию",
                                "business_reject:" + business.id(), "Закрыть заявление без активации"));
                    } else if (officialActions && "ACTIVE".equals(business.status())) {
                        actions.add(button(GuiIcon.LICENSE, "Выдать лицензию",
                                "license_choose:" + business.id(), "Выбрать тип и срок разрешения"));
                    }
                    putCentered(inventory, 2, actions);

                    List<ItemStack> licenses = new ArrayList<>();
                    for (BusinessService.LicenseView license : business.licenses()) {
                        if (licenses.size() >= 7) break;
                        String action = officialActions && "ACTIVE".equals(license.status())
                                ? "license_revoke:" + business.id() + ":" + license.type() : null;
                        ItemStack item = action == null
                                ? info(GuiIcon.LICENSE, license.type(), "Состояние: " + statusLabel(license.status()),
                                "До: " + date(license.expiresAt()))
                                : button(GuiIcon.LICENSE, license.type(), action,
                                "Состояние: " + statusLabel(license.status()), "До: " + date(license.expiresAt()),
                                "Отозвать разрешение");
                        licenses.add(item);
                    }
                    putCentered(inventory, 3, licenses);
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
                        inventory.setItem(22, info(GuiIcon.EMPTY, "Лицензий пока нет",
                                "Городская власть выдаёт их активным предприятиям"));
                        return;
                    }
                    List<ItemStack> cards = new ArrayList<>();
                    for (int i = 0; i < items.size() && i < LIST_SLOTS.length; i++) {
                        BusinessService.LicenseCard license = items.get(i);
                        cards.add(button(GuiIcon.LICENSE, license.type(),
                                "business_view:" + license.businessId(), "Предприятие: " + license.businessName(),
                                "Состояние: " + statusLabel(license.status()), "До: " + date(license.expiresAt())));
                    }
                    putCenteredGrid(inventory, 1, 4, cards);
                }));
    }

    private void renderEconomy(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Загрузка городской казны…"));
        storage.submit(() -> cities.view(player.getUniqueId()))
                .whenComplete((city, error) -> sync(player, inventory, () -> {
                    clearList(inventory);
                    if (error != null) {
                        inventory.setItem(22, errorItem(error));
                        return;
                    }
                    if (city == null || city.role() != CityRole.MAYOR) {
                        UiText.error(player, "Казна доступна только в рабочем кабинете мэра.");
                        navigate(player, Route.HOME);
                        return;
                    }
                    inventory.setItem(13, info(GuiIcon.TREASURY, "Казна · " + city.name(),
                            formatMinor(city.treasuryMinor()), "Внутренний счёт CityCore"));
                    putCentered(inventory, 2, List.of(button(GuiIcon.ACCOUNT, "Пополнить казну",
                            "prompt:treasury_deposit", "Перевести личные средства городу")));
                }));
    }

    private void renderCommunications(Player player, Inventory inventory) {
        inventory.setItem(22, info(GuiIcon.COMMUNICATIONS, "Связь вынесена в устройства",
                "Телефон открывается самим телефоном",
                "Частоты рации: Shift + ПКМ с рацией"));
    }

    private void renderPhoneDevice(Player player, Inventory inventory) {
        if (communication == null) return;
        Device phone = communication.devices().active(player.getUniqueId(), DeviceType.PHONE).orElse(null);
        if (phone == null) return;
        inventory.setItem(13, info(GuiIcon.PHONE, "Кнопочный телефон",
                "Модель: " + phone.model(), "Номер: " + phone.phoneNumber()));
        CallSession call = communication.call(player.getUniqueId());
        List<ItemStack> actions = new ArrayList<>();
        if (call == null) {
            actions.add(button(GuiIcon.CALL, "Позвонить", "phone_call_prompt",
                    "Указать имя игрока"));
        } else if (call.state() == CallState.RINGING && call.targetId().equals(player.getUniqueId())) {
            actions.add(button(GuiIcon.CONFIRM, "Принять", "phone_accept",
                    "Входящий звонок от " + playerName(call.callerId())));
            actions.add(button(GuiIcon.CANCEL, "Отклонить", "phone_decline",
                    "Завершить входящий вызов"));
        } else {
            actions.add(button(GuiIcon.CANCEL,
                    call.state() == CallState.CONNECTED ? "Завершить разговор" : "Отменить вызов",
                    "phone_hangup", "Собеседник: " + playerName(call.partner(player.getUniqueId()))));
        }
        putCentered(inventory, 2, actions);
    }

    private void renderRadioChannels(Player player, Inventory inventory) {
        if (communication == null) return;
        Device radio = communication.devices().active(player.getUniqueId(), DeviceType.RADIO).orElse(null);
        if (radio == null) return;
        String selected = communication.channel(player.getUniqueId());
        List<String> channels = communication.channels();
        int index = 0;
        for (int row = 1; row <= 3 && index < channels.size(); row++) {
            int count = Math.min(6, channels.size() - index);
            int[] slots = GuiLayout.centeredRow(row, count);
            for (int column = 0; column < slots.length; column++) {
                String channel = channels.get(index++);
                boolean active = channel.equals(selected);
                inventory.setItem(slots[column], button(active ? Material.LIME_CONCRETE : Material.LIGHT_BLUE_CONCRETE,
                        (active ? "● " : "") + channel, "radio_channel:" + channel,
                        active ? "Текущая частота" : "Переключить частоту"));
            }
        }
        putCentered(inventory, 4, List.of(button(
                communication.radioEnabled(player.getUniqueId()) ? Material.REDSTONE_TORCH : Material.LEVER,
                communication.radioEnabled(player.getUniqueId()) ? "Выключить передачу" : "Включить передачу",
                "radio_toggle", "Частота: " + selected)));
    }

    private void renderMayor(Player player, Inventory inventory) {
        inventory.setItem(10, loading("Проверяем полномочия мэра…"));
        storage.submit(() -> {
                    CityService.CityView city = cities.view(player.getUniqueId());
                    if (city == null || city.role() != CityRole.MAYOR) {
                        return new MayorWorkspaceData(city, List.of(), List.of(), List.of(), List.of(), List.of(), null);
                    }
                    return new MayorWorkspaceData(city, cities.pending(player.getUniqueId()),
                            businesses.list(player.getUniqueId(), false), businesses.list(player.getUniqueId(), true),
                            businesses.licenseRegistry(player.getUniqueId()),
                            cities.outgoingMayorTransfers(player.getUniqueId()),
                            cities.pendingMayorResignation(player.getUniqueId()));
                })
                .whenComplete((data, error) ->
                sync(player, inventory, () -> {
                    clearList(inventory);
                    if (error != null) { inventory.setItem(10, errorItem(error)); return; }
                    CityService.CityView city = data.city();
                    if (!capabilities.allows(player, city, Capability.MAYOR_WORKSPACE)) {
                        UiText.error(player, "Раздел мэрии доступен только действующему мэру.");
                        navigate(player, Route.HOME);
                        return;
                    }
                    inventory.setItem(13, info(GuiIcon.MAYOR, "Мэрия · " + city.name(),
                            "Рабочий раздел главы города", "Казна: " + formatMinor(city.treasuryMinor())));
                    List<ItemStack> actions = new ArrayList<>();
                    if (!data.citizenshipApplications().isEmpty()) actions.add(button(GuiIcon.APPLICATION,
                            "Заявления жителей · " + data.citizenshipApplications().size(), "route:CITY_APPLICATIONS",
                            "Принять или отклонить запросы"));
                    actions.add(button(GuiIcon.RESIDENTS, "Жители и должности", "route:CITY_MEMBERS",
                            "Назначение городских чиновников"));
                    if (!data.businesses().isEmpty()) actions.add(button(GuiIcon.BUSINESS,
                            "Предприятия · " + data.businesses().size(), "route:BUSINESS",
                            "Реестр компаний города"));
                    if (!data.pendingBusinesses().isEmpty()) actions.add(button(GuiIcon.REGISTRATION,
                            "Регистрации · " + data.pendingBusinesses().size(), "route:BUSINESS_PENDING",
                            "Решения по новым предприятиям"));
                    if (!data.licenses().isEmpty()) actions.add(button(GuiIcon.LICENSE,
                            "Лицензии · " + data.licenses().size(), "route:LICENSES",
                            "Действующие разрешения предприятий"));
                    actions.add(button(GuiIcon.TREASURY, "Казна", "route:ECONOMY",
                            "Баланс и пополнение городской казны"));
                    if (!data.outgoingTransfers().isEmpty()) {
                        CityService.MayorTransfer outgoing = data.outgoingTransfers().getFirst();
                        actions.add(button(GuiIcon.CANCEL, "Отменить передачу поста",
                                "mayor_transfer_cancel:" + outgoing.id(), "Предложение уже отправлено"));
                    } else if (data.resignation() == null) {
                        actions.add(button(GuiIcon.MAYOR, "Передать должность", "route:MAYOR_TRANSFER",
                                "Предложить пост жителю города"));
                        actions.add(button(GuiIcon.RESIGN, "Подать в отставку", "mayor_resign_prompt",
                                "Используется, если подходящего преемника нет"));
                    } else {
                        actions.add(button(GuiIcon.CANCEL, "Отозвать отставку",
                                "mayor_resignation_cancel:" + data.resignation().id(),
                                "Причина: " + shorten(data.resignation().reason(), 48)));
                    }
                    putCenteredGrid(inventory, 2, 3, actions);
                }));
    }

    private void renderGovernment(Player player, Inventory inventory) {
        inventory.setItem(10, loading("Проверяем полномочия госструктуры…"));
        boolean industryAdministrator = player.hasPermission("citycore.admin.industry");
        storage.submit(() -> {
                    CityService.CityView city = cities.view(player.getUniqueId());
                    if (city == null || (city.role() != CityRole.OFFICIAL && city.role() != CityRole.MAYOR)) {
                        return new GovernmentWorkspaceData(city, List.of(), List.of(), List.of(), List.of());
                    }
                    return new GovernmentWorkspaceData(city, businesses.list(player.getUniqueId(), false),
                            businesses.list(player.getUniqueId(), true), businesses.licenseRegistry(player.getUniqueId()),
                            industry == null ? List.of()
                                    : industry.pendingInspections(player.getUniqueId(), industryAdministrator));
                })
                .whenComplete((data, error) ->
                sync(player, inventory, () -> {
                    clearList(inventory);
                    if (error != null) { inventory.setItem(10, errorItem(error)); return; }
                    CityService.CityView city = data.city();
                    if (!capabilities.allows(player, city, Capability.GOVERNMENT_WORKSPACE)) {
                        UiText.error(player, "Служебный раздел доступен только городскому чиновнику.");
                        navigate(player, Route.HOME);
                        return;
                    }
                    inventory.setItem(13, info(GuiIcon.GOVERNMENT, "Управление · " + city.name(),
                            "Рабочие реестры городской службы", "Должность: " + roleLabel(city.role())));
                    List<ItemStack> actions = new ArrayList<>();
                    if (!data.businesses().isEmpty()) actions.add(button(GuiIcon.BUSINESS,
                            "Реестр предприятий · " + data.businesses().size(), "route:BUSINESS",
                            "Компании вашего города"));
                    if (!data.pendingBusinesses().isEmpty()) actions.add(button(GuiIcon.REGISTRATION,
                            "Регистрации · " + data.pendingBusinesses().size(), "route:BUSINESS_PENDING",
                            "Решения по новым компаниям"));
                    if (!data.licenses().isEmpty()) actions.add(button(GuiIcon.LICENSE,
                            "Лицензии · " + data.licenses().size(), "route:LICENSES",
                            "Выданные разрешения"));
                    if (!data.inspections().isEmpty()) actions.add(button(GuiIcon.INSPECTION,
                            "Промышленные осмотры · " + data.inspections().size(), "route:INDUSTRY_INSPECTIONS",
                            "Осмотреть существующие объекты"));
                    if (actions.isEmpty()) inventory.setItem(22, info(GuiIcon.CONFIRM,
                            "Новых задач нет", "Все доступные очереди обработаны"));
                    else putCenteredGrid(inventory, 2, 2, actions);
                }));
    }

    private void renderAdmin(Player player, Inventory inventory) {
        boolean reviewFoundations = capabilities.allows(player, null, Capability.REVIEW_CITY_FOUNDATIONS);
        boolean manageCities = player.hasPermission("citycore.admin.cities");
        inventory.setItem(22, loading("Загрузка административных задач…"));
        storage.submit(() -> new AdminWorkspaceData(
                        reviewFoundations ? cities.pendingFoundations() : List.of(),
                        manageCities ? cities.pendingMayorResignations() : List.of(),
                        manageCities ? cities.vacantCities() : List.of()))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    clearList(inventory);
                    if (error != null) { inventory.setItem(22, errorItem(error)); return; }
                    inventory.setItem(13, info(GuiIcon.ADMIN, "Администрирование",
                            "Только доступные операции проекта"));
                    List<ItemStack> actions = new ArrayList<>();
                    if (!data.foundations().isEmpty()) actions.add(button(GuiIcon.FOUNDATION,
                            "Основание городов · " + data.foundations().size(), "route:CITY_FOUNDATIONS_ADMIN",
                            "Рассмотреть новые заявки"));
                    if (capabilities.allows(player, null, Capability.ISSUE_CURRENCY)
                            && plugin.config().emissionEnabled()) actions.add(button(GuiIcon.EMISSION,
                            "Эмиссия", "route:EMISSION", "Формальное пополнение городской казны"));
                    if (!data.resignations().isEmpty()) actions.add(button(GuiIcon.RESIGN,
                            "Отставки мэров · " + data.resignations().size(), "route:MAYOR_RESIGNATIONS_ADMIN",
                            "Рассмотреть заявления без преемника"));
                    if (!data.vacancies().isEmpty()) actions.add(button(GuiIcon.MAYOR,
                            "Вакансии мэров · " + data.vacancies().size(), "route:MAYOR_VACANCIES_ADMIN",
                            "Назначить нового главу города"));
                    if (actions.isEmpty()) inventory.setItem(22, info(GuiIcon.CONFIRM,
                            "Активных задач нет", "Все административные очереди обработаны"));
                    else putCenteredGrid(inventory, 2, 2, actions);
                }));
    }

    private void renderFoundationApplications(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Загрузка заявок на основание…"));
        storage.submit(cities::pendingFoundations).whenComplete((items, error) -> sync(player, inventory, () -> {
            clearList(inventory);
            if (error != null) { inventory.setItem(22, errorItem(error)); return; }
            if (items.isEmpty()) {
                inventory.setItem(22, info(GuiIcon.EMPTY, "Очередь пуста", "Новых заявок на основание города нет"));
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
            inventory.setItem(10, info(GuiIcon.ERROR, "Заявка не выбрана",
                    "Вернитесь к очереди и выберите город"));
            return;
        }
        inventory.setItem(10, info(GuiIcon.FOUNDATION, application.name(), "ID: " + application.slug(),
                "Статус: " + statusLabel(application.status())));
        inventory.setItem(11, playerHead(application.founderId(), application.founderName(), null,
                "Предлагаемый основатель и первый мэр", "Подано: " + date(application.createdAt())));
        inventory.setItem(12, info(GuiIcon.DOCUMENTS, "Описание", application.description(),
                "Одобрение создаст город без начальной эмиссии"));
        inventory.setItem(19, button(GuiIcon.CONFIRM, "Одобрить основание",
                "foundation_approve:" + application.id(), "Создать город и назначить основателя мэром",
                "Решение будет записано в аудит"));
        inventory.setItem(20, button(GuiIcon.CANCEL, "Отклонить заявку",
                "foundation_reject:" + application.id(), "Закрыть заявку без создания города",
                "Решение будет записано в аудит"));
    }

    private void renderEmission(Player player, Inventory inventory) {
        inventory.setItem(10, info(GuiIcon.EMISSION, "Контролируемая эмиссия",
                "Получатель первого этапа: казна города",
                "Лимит операции: " + formatMinor(plugin.config().emissionMaxMinor())));
        inventory.setItem(11, loading("Загрузка городов и журнала…"));
        storage.submit(() -> new EmissionScreenData(cities.activeCities(), emission.recent(7)))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    for (int slot = 19; slot <= 34; slot++) inventory.setItem(slot, null);
                    if (error != null) { inventory.setItem(11, errorItem(error)); return; }
                    inventory.setItem(11, info(GuiIcon.TREASURY, "Казны-получатели",
                            "Выберите город, затем укажите сумму и основание"));
                    int targetLimit = Math.min(7, data.cities().size());
                    for (int i = 0; i < targetLimit; i++) {
                        CityService.CitySummary city = data.cities().get(i);
                        inventory.setItem(19 + i, button(GuiIcon.TREASURY, city.name(),
                                "emission_city:" + city.slug(), "Получатель: городская казна",
                                "ID города: " + city.slug(), "Далее: сумма и основание"));
                    }
                    if (targetLimit == 0) inventory.setItem(19, info(GuiIcon.EMPTY, "Нет активных городов",
                            "Эмиссию нельзя выполнить без казначейского счёта"));
                    int recent = 0;
                    for (EmissionService.Issue issue : data.issues()) {
                        if (recent >= 7) break;
                        inventory.setItem(28 + recent++, info(GuiIcon.EMISSION,
                                issue.targetName() + " · " + formatMinor(issue.amountMinor()),
                                "Состояние: " + statusLabel(issue.state()),
                                "Основание: " + shorten(issue.reason(), 42), "Дата: " + date(issue.createdAt())));
                    }
                    if (recent == 0) inventory.setItem(28, info(GuiIcon.EMPTY, "История пуста",
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
                        inventory.setItem(22, info(GuiIcon.ERROR, "Передача недоступна", "Требуется должность мэра")); return;
                    }
                    int index = 0;
                    for (CityService.Member member : data.members()) {
                        if (member.playerId().equals(player.getUniqueId()) || index >= LIST_SLOTS.length) continue;
                        inventory.setItem(LIST_SLOTS[index++], playerHead(member.playerId(), member.playerName(),
                                "mayor_transfer_request:" + member.playerId(), "Текущая роль: " + roleLabel(member.role()),
                                "Игрок должен отдельно принять предложение"));
                    }
                    if (index == 0) inventory.setItem(22, info(GuiIcon.EMPTY, "Нет кандидатов",
                            "Вернитесь в мэрию и подайте административное заявление об отставке"));
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
                        inventory.setItem(slot++, button(GuiIcon.CONFIRM, "Принять должность мэра",
                                "mayor_transfer_accept:" + item.id(), "Город: " + item.cityId(),
                                "Прежний мэр станет гражданином"));
                        inventory.setItem(slot++, button(GuiIcon.CANCEL, "Отклонить предложение",
                                "mayor_transfer_reject:" + item.id(), "Город: " + item.cityId(), "Роли не изменятся"));
                    }
                    if (items.isEmpty()) inventory.setItem(22, info(GuiIcon.EMPTY, "Предложений нет",
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
                inventory.setItem(slot++, info(GuiIcon.DOCUMENTS, "Заявление мэра", "Город: " + item.cityId(),
                        "Мэр: " + item.mayorId(), "Причина: " + shorten(item.reason(), 60)));
                inventory.setItem(slot++, button(GuiIcon.CONFIRM, "Одобрить отставку",
                        "mayor_resignation_approve:" + item.id(), "Город получит статус вакансии"));
                inventory.setItem(slot++, button(GuiIcon.CANCEL, "Отклонить отставку",
                        "mayor_resignation_reject:" + item.id(), "Мэр сохранит должность"));
            }
            if (items.isEmpty()) inventory.setItem(22, info(GuiIcon.EMPTY, "Очередь пуста", "Заявлений об отставке нет"));
        }));
    }

    private void renderMayorVacanciesAdmin(Player player, Inventory inventory) {
        inventory.setItem(22, loading("Загрузка городов без мэра…"));
        storage.submit(cities::vacantCities).whenComplete((items, error) -> sync(player, inventory, () -> {
            clearList(inventory);
            if (error != null) { inventory.setItem(22, errorItem(error)); return; }
            for (int i = 0; i < items.size() && i < LIST_SLOTS.length; i++) {
                CityService.CitySummary city = items.get(i);
                inventory.setItem(LIST_SLOTS[i], button(GuiIcon.MAYOR, city.name(),
                        "mayor_vacancy:" + city.id(), "ID: " + city.slug(), "Жителей: " + city.citizenCount(),
                        "Выбрать нового мэра"));
            }
            if (items.isEmpty()) inventory.setItem(22, info(GuiIcon.EMPTY, "Вакансий нет", "Во всех городах назначен мэр"));
        }));
    }

    private void renderMayorAppointmentsAdmin(Player player, Inventory inventory) {
        String cityId = selectedVacantCity.get(player.getUniqueId());
        if (cityId == null) { inventory.setItem(22, info(GuiIcon.ERROR, "Город не выбран", "Вернитесь к списку вакансий")); return; }
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
        if (businessId == null) { UiText.error(player, "Предприятие больше не выбрано."); navigate(player, Route.BUSINESS); return; }
        boolean industryAdministrator = player.hasPermission("citycore.admin.industry");
        inventory.setItem(22, loading("Загрузка доступных лицензий…"));
        storage.submit(() -> {
            BusinessService.BusinessDetail business = businesses.detail(player.getUniqueId(), businessId);
            List<IndustryService.ObjectView> objects = industry != null && "OIL_EXTRACTION".equals(business.activityType())
                    ? industry.objects(player.getUniqueId(), businessId, industryAdministrator)
                    : List.of();
            return new LicenseTypeData(business, objects);
        }).whenComplete((data, error) -> sync(player, inventory, () -> {
            clearList(inventory);
            if (error != null) { inventory.setItem(22, errorItem(error)); return; }
            inventory.setItem(13, info(GuiIcon.LICENSE, "Выберите разрешение",
                    "Показываются только применимые типы"));
            List<ItemStack> types = new ArrayList<>();
            types.add(button(GuiIcon.LICENSE, "Торговая лицензия", "license_type:TRADE",
                    "Разрешение для действующего предприятия"));
            if (!data.objects().isEmpty()) {
                types.add(button(GuiIcon.OIL, "Нефтедобыча I", "license_type:OIL_EXTRACTION_I",
                        "Для существующего объекта уровня I"));
                types.add(button(GuiIcon.OIL, "Нефтедобыча II", "license_type:OIL_EXTRACTION_II",
                        "Для существующего объекта уровня II"));
                types.add(button(GuiIcon.OIL, "Нефтедобыча III", "license_type:OIL_EXTRACTION_III",
                        "Для существующего объекта уровня III"));
            }
            putCentered(inventory, 2, types);
        }));
    }

    private void renderLicenseDuration(Player player, Inventory inventory) {
        String type = selectedLicenseType.get(player.getUniqueId());
        if (type == null || selectedBusiness.get(player.getUniqueId()) == null) {
            inventory.setItem(22, info(GuiIcon.ERROR, "Тип не выбран", "Вернитесь к выбору лицензии")); return;
        }
        int minimum = minimumLicenseDays(type);
        inventory.setItem(10, info(GuiIcon.LICENSE, "Срок лицензии", "Тип: " + type,
                "Минимальный срок: " + minimum + " дней", "Выберите срок действия"));
        if (minimum <= 7) inventory.setItem(19, button(GuiIcon.DOCUMENTS, "7 дней", "license_days:7", "Короткий испытательный срок"));
        if (minimum <= 30) inventory.setItem(20, button(GuiIcon.DOCUMENTS, "30 дней", "license_days:30", "Один календарный месяц"));
        if (minimum <= 90) inventory.setItem(21, button(GuiIcon.DOCUMENTS, "90 дней", "license_days:90", "Три месяца"));
        inventory.setItem(22, button(GuiIcon.DOCUMENTS, "365 дней", "license_days:365", "Один год"));
        inventory.setItem(23, button(GuiIcon.APPLICATION, "Другой срок", "license_days:custom", "Введите от 1 до 3650 дней"));
    }

    private void renderIndustryBusiness(Player player, Inventory inventory) {
        String businessId = selectedBusiness.get(player.getUniqueId());
        if (businessId == null) { inventory.setItem(22, info(GuiIcon.ERROR, "Предприятие не выбрано", "Откройте карточку предприятия")); return; }
        inventory.setItem(22, loading("Загрузка промышленного контура…"));
        boolean admin = player.hasPermission("citycore.admin.industry");
        storage.submit(() -> new IndustryBusinessData(businesses.detail(player.getUniqueId(), businessId),
                        industry.objects(player.getUniqueId(), businessId, admin),
                        industry.controllers(player.getUniqueId(), businessId)))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    clearList(inventory);
                    if (error != null) { inventory.setItem(22, errorItem(error)); return; }
                    inventory.setItem(10, info(GuiIcon.OIL, data.business().name(),
                            "Деятельность: " + activityLabel(data.business().activityType()),
                            "Объектов: " + data.objects().size() + " · контроллеров: " + data.controllers().size()));
                    int objectIndex = 0;
                    for (IndustryService.ObjectView object : data.objects()) {
                        if (objectIndex >= 7) break;
                        inventory.setItem(19 + objectIndex++, button(GuiIcon.INDUSTRY,
                                "Нефтяной объект" + (object.level() == null ? "" : " · " + roman(object.level())),
                                "industry_object:" + object.id(), "Состояние: " + statusLabel(object.status()),
                                "Месторождение: " + object.depositName(), "Остаток: " + object.reserveRemaining() + " ед."));
                    }
                    int controllerIndex = 0;
                    for (IndustryService.ControllerView controller : data.controllers()) {
                        if (controllerIndex >= 7) break;
                        inventory.setItem(28 + controllerIndex++, button(plugin.config().industry().controllerMaterial(),
                                controller.serial(), "industry_controller:" + controller.serial(),
                                "Состояние: " + statusLabel(controller.state()),
                                controller.worldName() == null ? "Контроллер ещё не размещён" : "Точка: " + controller.worldName() + " " + controller.x() + ", " + controller.y() + ", " + controller.z()));
                    }
                }));
    }

    private void renderIndustryController(Player player, Inventory inventory) {
        String serial = selectedController.get(player.getUniqueId());
        if (serial == null) { inventory.setItem(22, info(GuiIcon.ERROR, "Контроллер не выбран", "Откройте его из предприятия или нажмите ПКМ")); return; }
        boolean admin = player.hasPermission("citycore.admin.industry");
        inventory.setItem(22, loading("Проверяем контроллер…"));
        storage.submit(() -> industry.controller(player.getUniqueId(), serial, admin)).whenComplete((value, error) -> sync(player, inventory, () -> {
            clearList(inventory);
            if (error != null) { inventory.setItem(22, errorItem(error)); return; }
            selectedBusiness.put(player.getUniqueId(), value.businessId());
            inventory.setItem(10, info(plugin.config().industry().controllerMaterial(), "Контроллер " + value.serial(),
                    "Предприятие: " + value.businessName(), "Состояние: " + statusLabel(value.state()),
                    value.worldName() == null ? "Не размещён" : value.worldName() + " · " + value.x() + ", " + value.y() + ", " + value.z(),
                    value.worldName() == null ? "Установите предмет как обычный блок"
                            : "Чтобы снять контроллер, сломайте его блок"));
            if (value.objectId() != null) {
                inventory.setItem(19, button(GuiIcon.INDUSTRY, "Открыть связанный объект",
                        "industry_object:" + value.objectId(), "Карточка производства и расчётные циклы"));
                if ("PLACED".equals(value.state())) inventory.setItem(20, button(GuiIcon.CONTROLLER, "Восстановить привязку",
                        "controller_rebind:" + value.serial(), "Вернуть объект в состояние паузы после установки"));
            } else if ("PLACED".equals(value.state())) {
                inventory.setItem(19, info(GuiIcon.CONTROLLER, "Контроллер ожидает новую систему участков",
                        "Ручной выбор месторождения отключён", "Снимите контроллер или дождитесь Alpha 22"));
            } else if ("ISSUED".equals(value.state())) {
                inventory.setItem(19, info(GuiIcon.CONTROLLER, "Сначала установите контроллер",
                        "Поставьте предмет как блок в выбранной точке", "Затем нажмите по нему ПКМ"));
            }
        }));
    }

    private void renderIndustryDepositPick(Player player, Inventory inventory) {
        String serial = selectedController.get(player.getUniqueId()); String businessId = selectedBusiness.get(player.getUniqueId());
        if (serial == null || businessId == null) { inventory.setItem(22, info(GuiIcon.ERROR, "Контроллер не выбран", "Вернитесь к размещённому контроллеру")); return; }
        inventory.setItem(22, loading("Загрузка доступных месторождений…"));
        storage.submit(() -> industry.depositsForBusiness(player.getUniqueId(), businessId)).whenComplete((items, error) -> sync(player, inventory, () -> {
            clearList(inventory);
            if (error != null) { inventory.setItem(22, errorItem(error)); return; }
            for (int i = 0; i < items.size() && i < LIST_SLOTS.length; i++) {
                IndustryService.DepositView deposit = items.get(i);
                inventory.setItem(LIST_SLOTS[i], button(GuiIcon.DEPOSIT, deposit.name(),
                        "industry_apply:" + deposit.id(), "ID: " + deposit.slug(),
                        "Запас: " + deposit.reserveRemaining() + " / " + deposit.reserveTotal(),
                        "Лимит периода: " + deposit.throughputLimit()));
            }
            if (items.isEmpty()) inventory.setItem(22, info(GuiIcon.EMPTY, "Нет доступных месторождений",
                    "Администратор должен зарегистрировать ресурсный участок города"));
        }));
    }

    private void renderIndustryObject(Player player, Inventory inventory) {
        String objectId = selectedObject.get(player.getUniqueId());
        if (objectId == null) { inventory.setItem(22, info(GuiIcon.ERROR, "Объект не выбран", "Вернитесь к списку объектов")); return; }
        boolean admin = player.hasPermission("citycore.admin.industry");
        inventory.setItem(22, loading("Загрузка промышленного объекта…"));
        storage.submit(() -> new IndustryObjectData(industry.object(player.getUniqueId(), objectId, admin),
                        industry.recentCycles(player.getUniqueId(), objectId, admin, 7)))
                .whenComplete((data, error) -> sync(player, inventory, () -> {
                    clearList(inventory);
                    if (error != null) { inventory.setItem(22, errorItem(error)); return; }
                    IndustryService.ObjectView object = data.object();
                    selectedBusiness.put(player.getUniqueId(), object.businessId());
                    inventory.setItem(10, info(GuiIcon.INDUSTRY, "Нефтяной объект" + (object.level() == null ? "" : " · " + roman(object.level())),
                            "Предприятие: " + object.businessName(), "Состояние: " + statusLabel(object.status()),
                            object.lastError().isBlank() ? "Ошибок нет" : "Причина: " + shorten(object.lastError(), 60)));
                    inventory.setItem(11, info(GuiIcon.DEPOSIT, object.depositName(),
                            "Запас: " + object.reserveRemaining() + " / " + object.reserveTotal(),
                            "Лимит периода: " + object.throughputLimit()));
                    inventory.setItem(12, button(plugin.config().industry().controllerMaterial(), object.controllerSerial(),
                            "industry_controller:" + object.controllerSerial(),
                            "Контроллер: " + statusLabel(object.controllerState()),
                            "Лицензия: " + (object.level() == null ? "после инспекции" : "OIL_EXTRACTION_" + roman(object.level())),
                            "Открыть размещение и перепривязку"));
                    inventory.setItem(13, info(GuiIcon.ACCOUNT, "Финансовое состояние",
                            "Счёт: " + formatMinor(object.businessBalanceMinor()), "Долг: " + formatMinor(object.debtMinor()),
                            "Аренда за период: " + formatMinor(object.leaseMinor())));
                    inventory.setItem(14, info(GuiIcon.STATUS, "Расчётный период",
                            "Следующий: " + dateTime(object.nextCycleAt()), "Последний: " + dateTime(object.lastCycleAt())));

                    int action = 19;
                    if (object.owner()) {
                        if ("DRAFT".equals(object.status())) inventory.setItem(action++, button(GuiIcon.APPLICATION,
                                "Подать на осмотр", "industry_submit:" + object.id(), "Передать объект городскому инспектору"));
                        else if ("PENDING_INSPECTION".equals(object.status())) inventory.setItem(action++, button(GuiIcon.CANCEL,
                                "Отозвать заявку", "industry_withdraw:" + object.id(), "Вернуть контроллер в свободное состояние"));
                        else if ("REJECTED".equals(object.status()) || "CANCELLED".equals(object.status())) inventory.setItem(action++, button(GuiIcon.APPLICATION,
                                "Подать повторно", "industry_resubmit:" + object.id(), "После исправления замечаний инспектора"));
                        else if ("ACTIVE".equals(object.status())) inventory.setItem(action++, button(GuiIcon.STATUS,
                                "Остановить производство", "industry_pause:" + object.id(), "Обязательные платежи продолжат учитываться"));
                        else if (object.level() != null && !"DECOMMISSIONED".equals(object.status()) && !"DEPLETED".equals(object.status())) inventory.setItem(action++, button(GuiIcon.CONFIRM,
                                "Запустить производство", "industry_start:" + object.id(), "Проверить лицензию, контроллер, долг и запас"));
                        if (!"DECOMMISSIONED".equals(object.status())) inventory.setItem(action, button(GuiIcon.CANCEL,
                                "Вывести из эксплуатации", "industry_decommission:" + object.id(), "Необратимо остановить новые циклы", "Долг и история сохранятся"));
                    }
                    if (object.official() && "PENDING_INSPECTION".equals(object.status())) {
                        inventory.setItem(28, button(Material.COPPER_INGOT, "Одобрить уровень I", "industry_inspect:" + object.id() + ":1", "Малая установка"));
                        inventory.setItem(29, button(Material.IRON_INGOT, "Одобрить уровень II", "industry_inspect:" + object.id() + ":2", "Промышленная установка"));
                        inventory.setItem(30, button(Material.GOLD_INGOT, "Одобрить уровень III", "industry_inspect:" + object.id() + ":3", "Крупный комплекс"));
                        inventory.setItem(31, button(GuiIcon.CANCEL, "Отклонить проверку", "industry_inspect_reject:" + object.id(), "Указать причину отказа"));
                    }
                    int cycle = 0;
                    for (IndustryService.CycleView value : data.cycles()) {
                        if (cycle >= 7) break;
                        inventory.setItem(37 + cycle++, info(GuiIcon.STATUS, dateTime(value.dueAt()),
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
                inventory.setItem(LIST_SLOTS[i], button(GuiIcon.INSPECTION, object.businessName(),
                        "industry_object:" + object.id(), "Месторождение: " + object.depositName(),
                        "Контроллер: " + object.controllerSerial(), "Подано: " + date(object.submittedAt())));
            }
            if (items.isEmpty()) inventory.setItem(22, info(GuiIcon.EMPTY, "Очередь пуста", "Новых объектов на проверку нет"));
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
                inventory.setItem(LIST_SLOTS[i], info(GuiIcon.DEPOSIT, deposit.name(),
                        "ID: " + deposit.slug(), "Город: " + deposit.cityId(),
                        "Запас: " + deposit.reserveRemaining() + " / " + deposit.reserveTotal(),
                        "Участок: " + (deposit.assignedBusinessName() == null ? "свободен" : deposit.assignedBusinessName())));
            }
            for (int i = 0; i < data.objects().size() && i < 7; i++) {
                IndustryService.ObjectView object = data.objects().get(i);
                inventory.setItem(28 + i, button(GuiIcon.INDUSTRY, object.businessName(),
                        "industry_object:" + object.id(), "Объект: " + object.id().substring(0, 8),
                        "Состояние: " + statusLabel(object.status()), "Долг: " + formatMinor(object.debtMinor()),
                        "Открыть циклы и контроллер"));
            }
        }));
    }

    private void renderConfirmation(Player player, Inventory inventory) {
        Confirmation confirmation = confirmations.get(player.getUniqueId());
        if (confirmation == null) {
            inventory.setItem(10, info(GuiIcon.ERROR, "Действие устарело",
                    "Вернитесь назад и выберите его ещё раз"));
            return;
        }
        inventory.setItem(13, info(GuiIcon.DOCUMENTS, confirmation.title(), confirmation.description(),
                "Перед выполнением права и состояние проверятся снова"));
        inventory.setItem(21, button(GuiIcon.CONFIRM, confirmation.confirmLabel(), "confirm:run",
                "Подтвердить действие"));
        inventory.setItem(23, button(GuiIcon.CANCEL, "Отменить", "confirm:cancel",
                "Вернуться без изменений"));
    }

    private void renderNavigation(Player player, Route route, Inventory inventory) {
        if (route == Route.PHONE_DEVICE || route == Route.RADIO_CHANNELS) {
            inventory.setItem(GuiLayout.CLOSE_SLOT, button(GuiIcon.CLOSE, "Закрыть", "close", "Вернуться в игру"));
            return;
        }
        Route root = workspaceRoots.getOrDefault(player.getUniqueId(), Route.HOME);
        if (route != root) {
            inventory.setItem(GuiLayout.BACK_SLOT, button(GuiIcon.BACK, "Назад", "back",
                    "Вернуться к предыдущему разделу"));
            inventory.setItem(GuiLayout.HOME_SLOT, button(GuiIcon.HOME, workspaceTitle(root), "workspace_home",
                    "Вернуться к началу этого рабочего раздела"));
        }
        inventory.setItem(GuiLayout.CLOSE_SLOT, button(GuiIcon.CLOSE, "Закрыть", "close", "Вернуться в игру"));
    }

    public String action(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    public void handle(Player player, Route current, String action) {
        if (action == null) return;
        if (!action.equals("close") && !action.equals("back") && !action.equals("workspace_home")) {
            feedback.click(player);
        }
        if (action.equals("close")) {
            player.closeInventory();
        } else if (action.equals("back")) {
            navigate(player, backRoute(player, current));
        } else if (action.equals("workspace_home")) {
            navigate(player, workspaceRoots.getOrDefault(player.getUniqueId(), Route.HOME));
        } else if (action.startsWith("route:")) {
            Route target;
            try {
                target = Route.valueOf(action.substring(6));
            } catch (IllegalArgumentException ignored) {
                return;
            }
            if (target == Route.MAYOR || target == Route.GOVERNMENT || target == Route.ADMIN) {
                workspaceRoots.put(player.getUniqueId(), target);
            }
            if (target != Route.HOME) rememberReturn(player, target, current);
            navigate(player, target);
        } else if (action.startsWith("command:")) {
            player.closeInventory();
            player.performCommand("cc " + action.substring(8));
        } else if (action.equals("phone_call_prompt")) {
            if (communication == null) return;
            prompts.beginValidated(player, "Введите точное имя игрока для звонка:", value -> {
                String name = value.strip();
                if (!name.matches("[A-Za-z0-9_]{3,16}")) {
                    throw new IllegalArgumentException("Имя игрока: 3–16 символов, латиница, цифры и _. Пример: PuperSuperYT");
                }
                return name;
            }, name -> runCommunication(player, () -> communication.startCall(player, name), Route.PHONE_DEVICE));
        } else if (action.equals("phone_accept")) {
            if (communication != null) runCommunication(player, () -> communication.accept(player), Route.PHONE_DEVICE);
        } else if (action.equals("phone_decline")) {
            if (communication != null) runCommunication(player, () -> communication.decline(player), Route.PHONE_DEVICE);
        } else if (action.equals("phone_hangup")) {
            if (communication != null) runCommunication(player, () -> communication.hangup(player), Route.PHONE_DEVICE);
        } else if (action.equals("radio_toggle")) {
            if (communication != null) runCommunication(player, () -> communication.toggleRadio(player), current);
        } else if (action.startsWith("radio_channel:")) {
            if (communication != null) {
                String channel = action.substring("radio_channel:".length());
                try { communication.setChannel(player, channel); navigate(player, current); }
                catch (RuntimeException error) { feedback.failure(player); UiText.error(player, rootMessage(error)); }
            }
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
                    () -> { cities.cancelCitizenshipApplication(player.getUniqueId(), id); return "Заявление отозвано."; }, Route.CITY);
        } else if (action.startsWith("foundation_cancel:")) {
            String id = action.substring("foundation_cancel:".length());
            confirm(player, "Отозвать основание города", "Заявка будет закрыта без создания города.", "Отозвать",
                    () -> { cities.cancelFoundation(player.getUniqueId(), id); return "Заявка на основание отозвана."; }, Route.CITY);
        } else if (action.startsWith("mayor_transfer_request:")) {
            UUID candidate = uuid(action.substring("mayor_transfer_request:".length())); if (candidate == null) return;
            confirm(player, "Предложить должность мэра", "Кандидат должен отдельно принять предложение. До этого роли не изменятся.",
                    "Отправить предложение", () -> {
                        cities.requestMayorTransfer(player.getUniqueId(), candidate); return "Предложение кандидату отправлено.";
                    }, Route.MAYOR);
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
                    () -> { cities.cancelMayorTransfer(player.getUniqueId(), id); return "Предложение передачи должности отменено."; }, Route.MAYOR);
        } else if (action.equals("mayor_resign_prompt")) {
            prompts.begin(player, "Укажите причину административной отставки (5–180 символов):", reason ->
                    execute(player, () -> { cities.submitMayorResignation(player.getUniqueId(), reason); return "Заявление об отставке отправлено администрации."; }, Route.MAYOR));
        } else if (action.startsWith("mayor_resignation_cancel:")) {
            String id = action.substring("mayor_resignation_cancel:".length());
            confirm(player, "Отозвать заявление об отставке", "Администрация больше не сможет одобрить это заявление.",
                    "Отозвать заявление", () -> {
                        cities.cancelMayorResignation(player.getUniqueId(), id);
                        return "Заявление об отставке отозвано.";
                    }, Route.MAYOR);
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
        } else if (action.startsWith("business_activity:")) {
            BusinessDraft draft = businessDrafts.get(player.getUniqueId());
            if (draft == null) { UiText.error(player, "Черновик регистрации устарел. Начните создание заново."); return; }
            BusinessActivity activity;
            try { activity = BusinessActivity.parse(action.substring("business_activity:".length())); }
            catch (IllegalArgumentException invalid) { UiText.error(player, invalid.getMessage()); return; }
            BusinessDraft selected = draft.withActivity(activity);
            businessDrafts.put(player.getUniqueId(), selected);
            if (activity == BusinessActivity.OIL_EXTRACTION) {
                rememberReturn(player, Route.BUSINESS_OIL_LEVEL, Route.BUSINESS_ACTIVITY);
                navigate(player, Route.BUSINESS_OIL_LEVEL);
            } else {
                execute(player, () -> registerBusinessDraft(player, selected), Route.BUSINESS);
            }
        } else if (action.startsWith("business_oil_level:")) {
            BusinessDraft draft = businessDrafts.get(player.getUniqueId());
            if (draft == null || draft.activity() != BusinessActivity.OIL_EXTRACTION) {
                UiText.error(player, "Нефтяной черновик устарел. Начните создание заново."); return;
            }
            int level;
            try { level = Integer.parseInt(action.substring("business_oil_level:".length())); }
            catch (NumberFormatException invalid) { return; }
            if (level < 1 || level > 3) return;
            prompts.beginValidated(player,
                    "Кратко опишите нефтяной проект: оборудование, масштаб и план работы (15–180 символов).",
                    value -> boundedText(value, 15, 180, "Описание нефтяного проекта"), note -> {
                        BusinessDraft completed = draft.withOilSurvey(level, note);
                        businessDrafts.put(player.getUniqueId(), completed);
                        execute(player, () -> registerBusinessDraft(player, completed), Route.BUSINESS);
                    });
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
            prompts.beginValidated(player, "Шаг 1/3 · ID города: 3–24 символа, a-z, 0-9, _ или -. Пример: north_harbor", this::systemId, slug ->
                    prompts.beginValidated(player, "Шаг 2/3 · Отображаемое название города: 3–32 символа.",
                            value -> boundedText(value, 3, 32, "Название города"), name ->
                            prompts.beginValidated(player, "Шаг 3/3 · Кратко опишите идею города: 8–180 символов.",
                                    value -> boundedText(value, 8, 180, "Описание города"), description -> execute(player,
                                    () -> {
                                        cities.submitFoundation(player.getUniqueId(), slug, name, description);
                                        return "Заявка на основание города «" + name + "» отправлена администрации.";
                                    }, Route.CITY))));
        } else if (action.equals("prompt:business_register")) {
            prompts.beginValidated(player, "Шаг 1/2 · ID предприятия: 3–24 символа, a-z, 0-9, _ или -.", this::systemId, slug ->
                    prompts.beginValidated(player, "Шаг 2/2 · Название предприятия: 3–48 символов.",
                            value -> boundedText(value, 3, 48, "Название предприятия"), name -> {
                                businessDrafts.put(player.getUniqueId(), new BusinessDraft(slug, name));
                                navigate(player, Route.BUSINESS_ACTIVITY);
                            }));
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
        businessDrafts.remove(playerId);
        confirmations.remove(playerId);
        returnRoutes.remove(playerId);
        workspaceRoots.remove(playerId);
    }

    private String registerBusinessDraft(Player player, BusinessDraft draft) {
        BusinessService.BusinessView business = businesses.register(player.getUniqueId(), draft.slug(), draft.name(),
                draft.activity().name(), draft.requestedLevel(), draft.applicationNote());
        businessDrafts.remove(player.getUniqueId(), draft);
        if (business.reviewRequired()) {
            List<UUID> reviewers = businesses.reviewerIds(business.cityId());
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                for (UUID reviewerId : reviewers) {
                    if (reviewerId.equals(player.getUniqueId())) continue;
                    Player reviewer = Bukkit.getPlayer(reviewerId);
                    if (reviewer == null || !reviewer.isOnline()) continue;
                    UiText.info(reviewer, "Новая регистрация предприятия: «" + business.name() + "» · "
                            + activityLabel(business.activityType()) + ". Откройте /cc → служебный раздел.");
                }
            });
            return "Заявка «" + business.name() + "» отправлена городской службе.";
        }
        return "Небольшая торговая точка «" + business.name() + "» зарегистрирована автоматически.";
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

    private void runCommunication(Player player, Runnable operation, Route returnRoute) {
        try {
            operation.run();
            if (player.isOnline() && player.getOpenInventory().getTopInventory().getHolder(false) instanceof CityCoreHolder) {
                navigate(player, returnRoute);
            }
        } catch (RuntimeException error) {
            feedback.failure(player);
            UiText.error(player, rootMessage(error));
        }
    }

    private Route backRoute(Player player, Route route) {
        Map<Route, Route> remembered = returnRoutes.get(player.getUniqueId());
        if (remembered != null) {
            Route target = remembered.remove(route);
            if (target != null) return target;
        }
        return switch (route) {
            case HOME -> Route.HOME;
            case CITY_DIRECTORY, CITY_APPLICATIONS, CITY_MEMBERS, CITY_STATS -> Route.CITY;
            case CITY_APPLICATION_DETAIL -> Route.CITY_APPLICATIONS;
            case PHONE_DEVICE, RADIO_CHANNELS -> Route.COMMUNICATIONS;
            case COMMUNICATIONS -> Route.HOME;
            case BUSINESS_ACTIVITY -> Route.BUSINESS;
            case BUSINESS_OIL_LEVEL -> Route.BUSINESS_ACTIVITY;
            case BUSINESS_DETAIL, BUSINESS_PENDING, LICENSES -> Route.BUSINESS;
            case CITY_FOUNDATIONS_ADMIN, EMISSION -> Route.ADMIN;
            case CITY_FOUNDATION_DETAIL -> Route.CITY_FOUNDATIONS_ADMIN;
            case LICENSE_TYPE -> Route.BUSINESS_DETAIL;
            case LICENSE_DURATION -> Route.LICENSE_TYPE;
            case INDUSTRY_BUSINESS -> Route.BUSINESS_DETAIL;
            case INDUSTRY_CONTROLLER, INDUSTRY_OBJECT -> Route.INDUSTRY_BUSINESS;
            case INDUSTRY_DEPOSIT_PICK -> Route.INDUSTRY_CONTROLLER;
            case INDUSTRY_INSPECTIONS -> Route.GOVERNMENT;
            case INDUSTRY_DEPOSITS, MAYOR_RESIGNATIONS_ADMIN, MAYOR_VACANCIES_ADMIN -> Route.ADMIN;
            case MAYOR_APPOINT_ADMIN -> Route.MAYOR_VACANCIES_ADMIN;
            case MAYOR_TRANSFER -> Route.MAYOR;
            case MAYOR_TRANSFER_INBOX -> Route.PROFILE;
            case MAYOR, GOVERNMENT, ADMIN -> Route.HOME;
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

    private void renderFoundationShortcutAt(Inventory inventory, int slot, CityService.CityView city,
                                            CityService.FoundationApplication foundation) {
        if (city != null) {
            inventory.setItem(slot, info(GuiIcon.FOUNDATION, "Ваш город уже выбран",
                    "Вы уже состоите в городе «" + city.name() + "»"));
        } else if (foundation != null && "PENDING".equals(foundation.status())) {
            inventory.setItem(slot, info(GuiIcon.DOCUMENTS, "Основание · " + foundation.name(),
                    "Заявка ожидает решения администрации", "ID: " + foundation.slug(),
                    "Подано: " + date(foundation.createdAt())));
        } else {
            String previous = foundation != null && "REJECTED".equals(foundation.status())
                    ? "Предыдущая заявка была отклонена" : "Доступно игроку без гражданства";
            inventory.setItem(slot, button(GuiIcon.FOUNDATION, "Основать город", "prompt:city_foundation",
                    "Подать заявку администрации", previous));
        }
    }

    private void putCentered(Inventory inventory, int row, List<ItemStack> items) {
        int[] slots = GuiLayout.centeredRow(row, items.size());
        for (int i = 0; i < slots.length; i++) inventory.setItem(slots[i], items.get(i));
    }

    private void putCenteredGrid(Inventory inventory, int firstRow, int rowCount, List<ItemStack> items) {
        int index = 0;
        for (int row = firstRow; row < firstRow + rowCount && row <= 4 && index < items.size(); row++) {
            int count = Math.min(7, items.size() - index);
            for (int slot : GuiLayout.centeredRow(row, count)) inventory.setItem(slot, items.get(index++));
        }
    }

    private String workspaceTitle(Route root) {
        return switch (root) {
            case MAYOR -> "В мэрию";
            case GOVERNMENT -> "В госструктуры";
            case ADMIN -> "В администрирование";
            default -> "На главную";
        };
    }

    private boolean privilegedWorkspace(Player player) {
        Route root = workspaceRoots.getOrDefault(player.getUniqueId(), Route.HOME);
        return root == Route.MAYOR || root == Route.GOVERNMENT;
    }

    private ItemStack button(Material material, String name, String action, String... lore) {
        ItemStack item = info(material, name, lore);
        return buttonMeta(item, action);
    }

    private ItemStack button(GuiIcon icon, String name, String action, String... lore) {
        ItemStack item = info(icon, name, lore);
        return buttonMeta(item, action);
    }

    private ItemStack buttonMeta(ItemStack item, String action) {
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

    private ItemStack info(GuiIcon icon, String name, String... lore) {
        ItemStack item = icons.create(icon);
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
        return info(GuiIcon.LOADING, text, "Данные читаются без блокировки игрового потока");
    }

    private ItemStack errorItem(Throwable error) {
        return info(GuiIcon.ERROR, "Не удалось загрузить раздел", rootMessage(error),
                "Повторите попытку или передайте сообщение администратору");
    }

    private void decorate(Inventory inventory, Route route) {
        ItemStack dark = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        dark.editMeta(meta -> meta.displayName(Component.empty()));
        ItemStack gray = new ItemStack(Material.BLUE_STAINED_GLASS_PANE);
        gray.editMeta(meta -> meta.displayName(Component.empty()));
        for (int slot : GuiLayout.frameSlots()) {
            inventory.setItem(slot, (slot == 4 || slot == GuiLayout.BACK_SLOT || slot == GuiLayout.HOME_SLOT
                    || slot == GuiLayout.CLOSE_SLOT) ? dark : (slot % 2 == 0 ? dark : gray));
        }
        inventory.setItem(4, info(headerIcon(route), sectionLabel(route),
                "Доступны только работающие действия"));
    }

    private GuiIcon headerIcon(Route route) {
        return switch (route) {
            case HOME, PROFILE -> GuiIcon.BRAND;
            case CITY, CITY_STATS, CITY_DIRECTORY, CITY_APPLICATIONS, CITY_APPLICATION_DETAIL, CITY_MEMBERS,
                    MAYOR, MAYOR_TRANSFER, MAYOR_TRANSFER_INBOX -> GuiIcon.CITY;
            case BUSINESS, BUSINESS_ACTIVITY, BUSINESS_OIL_LEVEL, BUSINESS_DETAIL, BUSINESS_PENDING, LICENSES, ECONOMY, LICENSE_TYPE,
                    LICENSE_DURATION -> GuiIcon.BUSINESS;
            case COMMUNICATIONS, PHONE_DEVICE, RADIO_CHANNELS -> GuiIcon.COMMUNICATIONS;
            case ADMIN, CITY_FOUNDATIONS_ADMIN, CITY_FOUNDATION_DETAIL, EMISSION,
                    MAYOR_RESIGNATIONS_ADMIN, MAYOR_VACANCIES_ADMIN, MAYOR_APPOINT_ADMIN,
                    INDUSTRY_DEPOSITS -> GuiIcon.ADMIN;
            case GOVERNMENT, INDUSTRY_INSPECTIONS -> GuiIcon.GOVERNMENT;
            case INDUSTRY_BUSINESS, INDUSTRY_CONTROLLER, INDUSTRY_DEPOSIT_PICK, INDUSTRY_OBJECT -> GuiIcon.INDUSTRY;
            case CONFIRM -> GuiIcon.DOCUMENTS;
        };
    }

    private String sectionLabel(Route route) {
        return switch (route) {
            case HOME -> "CityCore · Центр игрока";
            case COMMUNICATIONS, PHONE_DEVICE, RADIO_CHANNELS -> "CityCore · Связь";
            case ADMIN, CITY_FOUNDATIONS_ADMIN, CITY_FOUNDATION_DETAIL, EMISSION,
                    MAYOR_RESIGNATIONS_ADMIN, MAYOR_VACANCIES_ADMIN, MAYOR_APPOINT_ADMIN,
                    INDUSTRY_DEPOSITS -> "CityCore · Управление системой";
            default -> title(null, route);
        };
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

    private String playerName(UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        return online == null ? Bukkit.getOfflinePlayer(playerId).getName() == null
                ? playerId.toString().substring(0, 8) : Bukkit.getOfflinePlayer(playerId).getName() : online.getName();
    }

    private long positiveMoney(String value) {
        long result;
        try { result = MinorUnits.parse(value, plugin.config().currencyScale()); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("Введите положительную сумму, например 100.00"); }
        if (result <= 0) throw new IllegalArgumentException("Сумма должна быть положительной.");
        return result;
    }

    private String systemId(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9_-]{2,23}")) {
            throw new IllegalArgumentException("ID: 3–24 символа; первый символ — буква или цифра; разрешены a-z, 0-9, _ и -. Пример: north_harbor");
        }
        return normalized;
    }

    private String boundedText(String value, int minimum, int maximum, String label) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() < minimum || normalized.length() > maximum) {
            throw new IllegalArgumentException(label + ": от " + minimum + " до " + maximum + " символов. Сейчас: " + normalized.length() + ".");
        }
        return normalized;
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
    private String activityLabel(String activity) { return switch (activity) {
        case "GENERAL" -> "общее предприятие";
        case "SMALL_RETAIL" -> "небольшая торговая точка";
        case "TRADE" -> "торговое предприятие";
        case "SERVICES" -> "оказание услуг";
        case "MANUFACTURING" -> "производство";
        case "OIL_EXTRACTION" -> "нефтедобыча";
        default -> activity.toLowerCase(Locale.ROOT);
    }; }

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
            case CITY_STATS -> "CityCore · Статистика города";
            case CITY_DIRECTORY -> "CityCore · Каталог городов";
            case CITY_APPLICATIONS -> "CityCore · Заявления";
            case CITY_APPLICATION_DETAIL -> "CityCore · Решение по заявлению";
            case CITY_MEMBERS -> "CityCore · Жители";
            case BUSINESS -> "CityCore · Предприятия";
            case BUSINESS_ACTIVITY -> "CityCore · Направление предприятия";
            case BUSINESS_OIL_LEVEL -> "CityCore · Оценка нефтевышки";
            case BUSINESS_PENDING -> "CityCore · Регистрации";
            case BUSINESS_DETAIL -> "CityCore · Карточка предприятия";
            case LICENSES -> "CityCore · Лицензии";
            case ECONOMY -> "CityCore · Финансы";
            case COMMUNICATIONS -> "CityCore · Связь";
            case PHONE_DEVICE -> "CityCore · Телефон";
            case RADIO_CHANNELS -> "CityCore · Каналы рации";
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
    private record HomeScreenData(CityService.CityView city, boolean foundationWork,
                                  boolean cityWork, boolean emissionWork, int businessReviews) {
        boolean adminWorkAvailable() { return foundationWork || cityWork || emissionWork; }
    }
    private record ProfileScreenData(CityService.CityView city,
                                     List<CityService.MayorTransfer> incomingTransfers) {}
    private record CityScreenData(CityService.CityView city, List<CityService.PlayerApplication> applications,
                                  CityService.FoundationApplication foundation) {}
    private record CityStatsScreenData(CityService.CityView city, CityService.CityStats stats) {}
    private record MemberScreenData(CityService.CityView city, List<CityService.Member> members) {}
    private record BusinessScreenData(CityService.CityView city, List<BusinessService.BusinessView> items) {}
    private record BusinessDraft(String slug, String name, BusinessActivity activity,
                                 Integer requestedLevel, String applicationNote) {
        BusinessDraft(String slug, String name) { this(slug, name, null, null, ""); }
        BusinessDraft withActivity(BusinessActivity next) { return new BusinessDraft(slug, name, next, null, ""); }
        BusinessDraft withOilSurvey(int level, String note) {
            return new BusinessDraft(slug, name, BusinessActivity.OIL_EXTRACTION, level, note);
        }
    }
    private record BusinessDetailScreenData(BusinessService.BusinessDetail business,
                                            List<IndustryService.ObjectView> objects) {}
    private record LicenseTypeData(BusinessService.BusinessDetail business,
                                   List<IndustryService.ObjectView> objects) {}
    private record MayorWorkspaceData(CityService.CityView city,
                                      List<CityService.Application> citizenshipApplications,
                                      List<BusinessService.BusinessView> businesses,
                                      List<BusinessService.BusinessView> pendingBusinesses,
                                      List<BusinessService.LicenseCard> licenses,
                                      List<CityService.MayorTransfer> outgoingTransfers,
                                      CityService.MayorResignation resignation) {}
    private record GovernmentWorkspaceData(CityService.CityView city,
                                           List<BusinessService.BusinessView> businesses,
                                           List<BusinessService.BusinessView> pendingBusinesses,
                                           List<BusinessService.LicenseCard> licenses,
                                           List<IndustryService.ObjectView> inspections) {}
    private record AdminWorkspaceData(List<CityService.FoundationApplication> foundations,
                                      List<CityService.MayorResignation> resignations,
                                      List<CityService.CitySummary> vacancies) {}
    private record EmissionScreenData(List<CityService.CitySummary> cities, List<EmissionService.Issue> issues) {}
    private record IndustryBusinessData(BusinessService.BusinessDetail business,
                                        List<IndustryService.ObjectView> objects,
                                        List<IndustryService.ControllerView> controllers) {}
    private record IndustryObjectData(IndustryService.ObjectView object, List<IndustryService.CycleView> cycles) {}
    private record AdminIndustryData(List<IndustryService.DepositView> deposits,
                                     List<IndustryService.ObjectView> objects) {}
}
