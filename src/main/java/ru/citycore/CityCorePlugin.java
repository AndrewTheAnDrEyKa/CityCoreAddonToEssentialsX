package ru.citycore;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import ru.citycore.command.CityCoreCommand;
import ru.citycore.city.CityService;
import ru.citycore.business.BusinessService;
import ru.citycore.config.CityCoreConfig;
import ru.citycore.db.Database;
import ru.citycore.db.Migrations;
import ru.citycore.db.DatabaseBackup;
import ru.citycore.db.StorageExecutor;
import ru.citycore.economy.EconomyGateway;
import ru.citycore.economy.EmissionService;
import ru.citycore.economy.VaultEconomyGateway;
import ru.citycore.economy.InternalLedger;
import ru.citycore.economy.VaultTransferRepository;
import ru.citycore.economy.VaultTransferCoordinator;
import ru.citycore.gui.GuiListener;
import ru.citycore.gui.GuiService;
import ru.citycore.gui.GuiFeedback;
import ru.citycore.gui.ChatPromptService;
import ru.citycore.profile.ProfileListener;
import ru.citycore.profile.ProfileRepository;
import ru.citycore.permission.RoleMirror;
import ru.citycore.permission.RoleMirrors;
import ru.citycore.industry.IndustryService;
import ru.citycore.industry.ControllerItems;
import ru.citycore.industry.ControllerListener;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

public final class CityCorePlugin extends JavaPlugin {
    private CityCoreConfig config;
    private Database database;
    private StorageExecutor storage;
    private EconomyGateway economy;

    @Override public void onEnable() {
        saveDefaultConfig();
        migrateConfigIfNeeded();
        reloadCityCoreConfig();
        try {
            Path dataDirectory = getDataFolder().toPath().toAbsolutePath().normalize();
            Path databasePath = dataDirectory.resolve(config.databaseFile()).normalize();
            if (!databasePath.startsWith(dataDirectory)) {
                throw new IllegalArgumentException("database.file должен находиться внутри папки CityCore");
            }
            Path backup = DatabaseBackup.beforeAlpha18(databasePath, dataDirectory.resolve("backups"));
            if (backup != null) getLogger().info("Резервная копия перед Alpha 18: " + backup.getFileName());
            database = new Database(databasePath, config.poolSize());
            Migrations.apply(database);
            storage = new StorageExecutor();
            Economy vault = requireEconomy();
            economy = new VaultEconomyGateway(vault, config.currencyScale());

            ProfileRepository profiles = new ProfileRepository(database);
            InternalLedger ledger = new InternalLedger(database);
            CityService cities = new CityService(database, () -> config.citizenshipReapplyCooldownSeconds());
            BusinessService businesses = new BusinessService(database, type -> switch (type) {
                case "OIL_EXTRACTION_I" -> config.industry().level(1).minimumLicenseDays();
                case "OIL_EXTRACTION_II" -> config.industry().level(2).minimumLicenseDays();
                case "OIL_EXTRACTION_III" -> config.industry().level(3).minimumLicenseDays();
                default -> 1;
            });
            IndustryService industry = new IndustryService(database, ledger, () -> config.industry());
            EmissionService emission = new EmissionService(database, ledger, () -> config.emissionMaxMinor());
            int recoveredIssues = emission.recoverIncomplete();
            GuiFeedback feedback = new GuiFeedback(this);
            VaultTransferCoordinator vaultTransfers = new VaultTransferCoordinator(this, storage, economy, cities,
                    businesses, new VaultTransferRepository(database), ledger, feedback);
            vaultTransfers.recoverIncomplete();
            ChatPromptService prompts = new ChatPromptService(this, feedback);
            RoleMirror roleMirror = RoleMirrors.create(this);
            ControllerItems controllerItems = new ControllerItems(this);
            GuiService gui = new GuiService(this, economy, storage, cities, businesses, prompts, feedback,
                    vaultTransfers, emission, roleMirror, industry, controllerItems);
            getServer().getPluginManager().registerEvents(new ProfileListener(this, storage, profiles, cities, roleMirror), this);
            getServer().getPluginManager().registerEvents(new GuiListener(gui), this);
            getServer().getPluginManager().registerEvents(prompts, this);
            ControllerListener controllerListener = new ControllerListener(this, storage, industry, controllerItems, gui);
            getServer().getPluginManager().registerEvents(controllerListener, this);
            controllerListener.start();
            if (config.industry().enabled()) {
                getServer().getScheduler().runTaskTimer(this, () -> storage.submit(() -> industry.processDue(Instant.now()))
                        .exceptionally(error -> {
                            getLogger().warning("Ошибка промышленного планировщика: " + error.getMessage());
                            return null;
                        }), 100L, 200L);
            }
            CityCoreCommand command = new CityCoreCommand(this, gui, storage, cities, businesses, vaultTransfers, roleMirror);
            var registered = getCommand("citycore");
            if (registered == null) throw new IllegalStateException("Команда citycore отсутствует в plugin.yml");
            registered.setExecutor(command); registered.setTabCompleter(command);
            for (String commandName : new String[]{"citycoreadmin", "citycoremayor", "citycoregovernment"}) {
                var shortcut = getCommand(commandName);
                if (shortcut == null) throw new IllegalStateException("Команда " + commandName + " отсутствует в plugin.yml");
                shortcut.setExecutor(command);
            }

            if (!getServer().getOnlineMode() && config.warnOfflineMode()) {
                getLogger().warning("Сервер работает в offline-mode: UUID не подтверждаются Mojang и могут быть подменены.");
            }
            getLogger().info("CityCore " + getPluginMeta().getVersion() + " включён; schema=" + Migrations.latestVersion() + "; economy="
                    + vault.getName() + "; recoveredIssues=" + recoveredIssues + "; luckPerms=" + roleMirror.available());
        } catch (Exception exception) {
            getLogger().severe("CityCore не может безопасно запуститься: " + exception.getMessage());
            getSLF4JLogger().error("Startup failure", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override public void onDisable() {
        if (storage != null) storage.close();
        if (database != null) database.close();
    }

    private Economy requireEconomy() {
        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider == null || provider.getProvider() == null) throw new IllegalStateException("Vault Economy provider не найден");
        return provider.getProvider();
    }

    public void reloadCityCoreConfig() {
        reloadConfig(); CityCoreConfig next = CityCoreConfig.from(getConfig());
        if (config != null) {
            if (!config.databaseFile().equals(next.databaseFile()) || config.poolSize() != next.poolSize()) {
                throw new IllegalStateException("Изменение database.file или database.pool-size требует полного перезапуска сервера");
            }
            if (config.currencyScale() != next.currencyScale()) {
                throw new IllegalStateException("Изменение economy.currency-scale требует полного перезапуска сервера");
            }
            if (config.industry().enabled() != next.industry().enabled()
                    || config.industry().controllerMaterial() != next.industry().controllerMaterial()) {
                throw new IllegalStateException("Изменение industry.enabled или industry.controller-material требует полного перезапуска сервера");
            }
            if (config.luckPermsEnabled() != next.luckPermsEnabled()
                    || !config.luckPermsGroups().equals(next.luckPermsGroups())) {
                throw new IllegalStateException("Изменение интеграции LuckPerms требует полного перезапуска сервера");
            }
        }
        config = next;
    }
    private void migrateConfigIfNeeded() {
        File file = new File(getDataFolder(), "config.yml");
        YamlConfiguration raw = YamlConfiguration.loadConfiguration(file);
        int version = raw.getInt("config-version", 1);
        if (version < 2) {
            raw.set("gui.sound-open", true);
            raw.set("gui.sound-click", true);
            raw.set("gui.sound-results", true);
            raw.set("gui.sound-open-effect", "BLOCK_AMETHYST_BLOCK_CHIME");
            raw.set("gui.sound-click-effect", "UI_BUTTON_CLICK");
            raw.set("gui.sound-success-effect", "ENTITY_EXPERIENCE_ORB_PICKUP");
            raw.set("gui.sound-failure-effect", "BLOCK_NOTE_BLOCK_BASS");
            raw.set("gui.sound-prompt-effect", "BLOCK_NOTE_BLOCK_HAT");
        }
        if (version < 3) {
            raw.set("economy.emission-enabled", true);
            raw.set("economy.emission-max-amount", "1000000.00");
            raw.set("integrations.luckperms.enabled", true);
            raw.set("integrations.luckperms.groups.default", "citycore_default");
            raw.set("integrations.luckperms.groups.citizen", "citycore_default");
            raw.set("integrations.luckperms.groups.official", "citycore_official");
            raw.set("integrations.luckperms.groups.mayor", "citycore_mayor");
            raw.set("integrations.luckperms.groups.police", "citycore_police");
        }
        if (version < 4) {
            raw.set("citizenship.reapply-cooldown-seconds", 86400L);
            raw.set("industry.enabled", true);
            raw.set("industry.cycle-seconds", 3600L);
            raw.set("industry.max-catch-up-periods", 3);
            raw.set("industry.default-lease", "20.00");
            raw.set("industry.debt-suspend-threshold", "1000.00");
            raw.set("industry.default-tax-bps", 500);
            raw.set("industry.max-tax-bps", 2500);
            raw.set("industry.controller-material", "LODESTONE");
            raw.set("industry.levels.1.units-per-cycle", 10L);
            raw.set("industry.levels.1.price-per-unit", "25.00");
            raw.set("industry.levels.1.maintenance", "40.00");
            raw.set("industry.levels.1.minimum-license-days", 7);
            raw.set("industry.levels.2.units-per-cycle", 25L);
            raw.set("industry.levels.2.price-per-unit", "25.00");
            raw.set("industry.levels.2.maintenance", "120.00");
            raw.set("industry.levels.2.minimum-license-days", 30);
            raw.set("industry.levels.3.units-per-cycle", 60L);
            raw.set("industry.levels.3.price-per-unit", "25.00");
            raw.set("industry.levels.3.maintenance", "360.00");
            raw.set("industry.levels.3.minimum-license-days", 90);
        }
        if (version >= 4) return;
        raw.set("config-version", 4);
        try {
            raw.save(file);
        } catch (IOException error) {
            throw new IllegalStateException("Не удалось обновить config.yml до версии 4", error);
        }
    }
    public CityCoreConfig config() { return config; }
    public EconomyGateway economy() { return economy; }
}
