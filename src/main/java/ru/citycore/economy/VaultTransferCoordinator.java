package ru.citycore.economy;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import ru.citycore.CityCorePlugin;
import ru.citycore.business.BusinessService;
import ru.citycore.city.CityService;
import ru.citycore.db.StorageExecutor;
import ru.citycore.gui.GuiFeedback;
import ru.citycore.gui.UiText;

import java.util.function.Supplier;

public final class VaultTransferCoordinator {
    private final CityCorePlugin plugin; private final StorageExecutor storage; private final EconomyGateway economy;
    private final CityService cities; private final BusinessService businesses;
    private final VaultTransferRepository transfers; private final InternalLedger ledger;
    private final GuiFeedback feedback;
    private final String clearingAccount;

    public VaultTransferCoordinator(CityCorePlugin plugin, StorageExecutor storage, EconomyGateway economy,
                                    CityService cities, BusinessService businesses,
                                    VaultTransferRepository transfers, InternalLedger ledger,
                                    GuiFeedback feedback) {
        this.plugin = plugin; this.storage = storage; this.economy = economy; this.cities = cities;
        this.businesses = businesses; this.transfers = transfers; this.ledger = ledger;
        this.feedback = feedback;
        this.clearingAccount = ledger.ensureAccount("SYSTEM", "VAULT_CLEARING", "ESSENTIALS");
    }

    public void depositToTreasury(Player player, long amountMinor) {
        deposit(player, amountMinor, () -> cities.treasuryAccount(player.getUniqueId()), "Городская казна пополнена.");
    }

    public void depositToBusiness(Player player, String businessId, long amountMinor) {
        deposit(player, amountMinor, () -> businesses.accountForOwner(player.getUniqueId(), businessId), "Счёт предприятия пополнен.");
    }

    private void deposit(Player player, long amountMinor, Supplier<String> account, String successMessage) {
        if (amountMinor <= 0) { message(player, "Сумма должна быть положительной.", NamedTextColor.RED); return; }
        long balance = economy.balanceMinor(player.getUniqueId());
        if (balance < amountMinor) { message(player, "Недостаточно средств в личном кошельке.", NamedTextColor.RED); return; }
        storage.submit(() -> transfers.prepare(player.getUniqueId(), account.get(), amountMinor, balance))
                .whenComplete((transfer, prepareError) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (prepareError != null) { message(player, "Перевод не подготовлен: " + rootMessage(prepareError), NamedTextColor.RED); return; }
                    storage.submit(() -> { transfers.transition(transfer.id(), "PREPARED", "VAULT_REQUESTED", null); return null; })
                            .whenComplete((ignored, stateError) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                                if (stateError != null) { message(player, "Перевод остановлен до списания.", NamedTextColor.RED); return; }
                                EconomyGateway.TransferResult result = economy.withdraw(player.getUniqueId(), amountMinor);
                                if (!result.successful()) {
                                    storage.submit(() -> { transfers.transition(transfer.id(), "VAULT_REQUESTED", "FAILED", result.message()); return null; });
                                    message(player, "Vault отклонил перевод: " + result.message(), NamedTextColor.RED); return;
                                }
                                finishWithdrawn(player, transfer, successMessage);
                            }));
                }));
    }

    public void recoverIncomplete() {
        storage.submit(() -> {
            for (var transfer : transfers.incomplete()) {
                switch (transfer.state()) {
                    case "PREPARED" -> transfers.transition(transfer.id(), "PREPARED", "FAILED", "Server restarted before Vault request");
                    case "VAULT_REQUESTED" -> {
                        transfers.transition(transfer.id(), "VAULT_REQUESTED", "MANUAL_REVIEW", "Vault outcome is ambiguous after restart");
                        plugin.getLogger().severe("Vault transfer " + transfer.id() + " требует ручной сверки: результат списания неизвестен.");
                    }
                    case "VAULT_WITHDRAWN" -> {
                        ledger.transfer(transfer.idempotencyKey(), clearingAccount, transfer.targetAccount(), transfer.amountMinor(), "VAULT_DEPOSIT", transfer.playerId());
                        transfers.transition(transfer.id(), "VAULT_WITHDRAWN", "COMPLETED", null);
                        plugin.getLogger().warning("Vault transfer " + transfer.id() + " восстановлен без повторного списания.");
                    }
                    default -> plugin.getLogger().warning("Неизвестное состояние Vault transfer " + transfer.id() + ": " + transfer.state());
                }
            }
            return null;
        }).exceptionally(error -> { plugin.getSLF4JLogger().error("Vault recovery failed", error); return null; });
    }

    private void finishWithdrawn(Player player, VaultTransferRepository.Transfer transfer, String successMessage) {
        storage.submit(() -> {
            transfers.transition(transfer.id(), "VAULT_REQUESTED", "VAULT_WITHDRAWN", null);
            ledger.transfer(transfer.idempotencyKey(), clearingAccount, transfer.targetAccount(), transfer.amountMinor(), "VAULT_DEPOSIT", transfer.playerId());
            transfers.transition(transfer.id(), "VAULT_WITHDRAWN", "COMPLETED", null);
            return null;
        }).whenComplete((ignored, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("Vault transfer {} requires recovery", transfer.id(), error);
                message(player, "Деньги списаны, но операция ожидает восстановления. ID: " + transfer.id(), NamedTextColor.RED);
            } else message(player, successMessage, NamedTextColor.GREEN);
        }));
    }

    private void message(Player player, String text, NamedTextColor color) {
        if (!player.isOnline()) return;
        if (color == NamedTextColor.GREEN) {
            feedback.success(player);
            UiText.success(player, text);
        } else if (color == NamedTextColor.RED) {
            feedback.failure(player);
            UiText.error(player, text);
        } else UiText.send(player, text, color);
    }
    private String rootMessage(Throwable error) { Throwable value = error; while (value.getCause() != null) value = value.getCause(); return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage(); }
}
