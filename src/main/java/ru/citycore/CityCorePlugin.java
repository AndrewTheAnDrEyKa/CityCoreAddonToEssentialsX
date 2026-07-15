package ru.citycore;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import ru.citycore.command.CityCoreCommand;
import ru.citycore.city.CityService;
import ru.citycore.business.BusinessService;
import ru.citycore.config.CityCoreConfig;
import ru.citycore.db.Database;
import ru.citycore.db.Migrations;
import ru.citycore.db.StorageExecutor;
import ru.citycore.economy.EconomyGateway;
import ru.citycore.economy.VaultEconomyGateway;
import ru.citycore.economy.InternalLedger;
import ru.citycore.economy.VaultTransferRepository;
import ru.citycore.economy.VaultTransferCoordinator;
import ru.citycore.gui.GuiListener;
import ru.citycore.gui.GuiService;
import ru.citycore.profile.ProfileListener;
import ru.citycore.profile.ProfileRepository;

import java.nio.file.Path;

public final class CityCorePlugin extends JavaPlugin {
    private CityCoreConfig config;
    private Database database;
    private StorageExecutor storage;
    private EconomyGateway economy;

    @Override public void onEnable() {
        saveDefaultConfig();
        reloadCityCoreConfig();
        try {
            Path dataDirectory = getDataFolder().toPath().toAbsolutePath().normalize();
            Path databasePath = dataDirectory.resolve(config.databaseFile()).normalize();
            if (!databasePath.startsWith(dataDirectory)) {
                throw new IllegalArgumentException("database.file должен находиться внутри папки CityCore");
            }
            database = new Database(databasePath, config.poolSize());
            Migrations.apply(database);
            storage = new StorageExecutor();
            Economy vault = requireEconomy();
            economy = new VaultEconomyGateway(vault, config.currencyScale());

            ProfileRepository profiles = new ProfileRepository(database);
            CityService cities = new CityService(database);
            BusinessService businesses = new BusinessService(database);
            InternalLedger ledger = new InternalLedger(database);
            VaultTransferCoordinator vaultTransfers = new VaultTransferCoordinator(this, storage, economy, cities,
                    new VaultTransferRepository(database), ledger);
            vaultTransfers.recoverIncomplete();
            GuiService gui = new GuiService(this, economy, storage, cities, businesses);
            getServer().getPluginManager().registerEvents(new ProfileListener(this, storage, profiles), this);
            getServer().getPluginManager().registerEvents(new GuiListener(gui), this);
            CityCoreCommand command = new CityCoreCommand(this, gui, storage, cities, businesses, vaultTransfers);
            var registered = getCommand("citycore");
            if (registered == null) throw new IllegalStateException("Команда citycore отсутствует в plugin.yml");
            registered.setExecutor(command); registered.setTabCompleter(command);

            if (!getServer().getOnlineMode() && config.warnOfflineMode()) {
                getLogger().warning("Сервер работает в offline-mode: UUID не подтверждаются Mojang и могут быть подменены.");
            }
            getLogger().info("CityCore " + getPluginMeta().getVersion() + " включён; schema=3; economy=" + vault.getName());
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
        if (config != null && config.currencyScale() != next.currencyScale()) {
            throw new IllegalStateException("Изменение economy.currency-scale требует полного перезапуска сервера");
        }
        config = next;
    }
    public CityCoreConfig config() { return config; }
    public EconomyGateway economy() { return economy; }
}
