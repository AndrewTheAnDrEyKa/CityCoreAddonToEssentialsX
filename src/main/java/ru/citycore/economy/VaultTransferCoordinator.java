package ru.citycore.economy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import ru.citycore.CityCorePlugin;
import ru.citycore.city.CityService;
import ru.citycore.db.StorageExecutor;

public final class VaultTransferCoordinator {
    private final CityCorePlugin plugin; private final StorageExecutor storage; private final EconomyGateway economy;
    private final CityService cities; private final VaultTransferRepository transfers; private final InternalLedger ledger;
    private final String clearingAccount;

    public VaultTransferCoordinator(CityCorePlugin plugin, StorageExecutor storage, EconomyGateway economy,
                                    CityService cities, VaultTransferRepository transfers, InternalLedger ledger) {
        this.plugin = plugin; this.storage = storage; this.economy = economy; this.cities = cities; this.transfers = transfers; this.ledger = ledger;
        this.clearingAccount = ledger.ensureAccount("SYSTEM", "VAULT_CLEARING", "ESSENTIALS");
    }

    public void depositToTreasury(Player player, long amountMinor) {
        if (amountMinor <= 0) { message(player, "Сумма должна быть положительной.", NamedTextColor.RED); return; }
        long balance = economy.balanceMinor(player.getUniqueId());
        if (balance < amountMinor) { message(player, "Недостаточно средств в личном кошельке.", NamedTextColor.RED); return; }
        storage.submit(() -> transfers.prepare(player.getUniqueId(), cities.treasuryAccount(player.getUniqueId()), amountMinor, balance))
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
                                finishWithdrawn(player, transfer);
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
                        ledger.transfer(transfer.idempotencyKey(), clearingAccount, transfer.targetAccount(), transfer.amountMinor(), "VAULT_DEPOSIT_TO_TREASURY", transfer.playerId());
                        transfers.transition(transfer.id(), "VAULT_WITHDRAWN", "COMPLETED", null);
                        plugin.getLogger().warning("Vault transfer " + transfer.id() + " восстановлен без повторного списания.");
                    }
                    default -> plugin.getLogger().warning("Неизвестное состояние Vault transfer " + transfer.id() + ": " + transfer.state());
                }
            }
            return null;
        }).exceptionally(error -> { plugin.getSLF4JLogger().error("Vault recovery failed", error); return null; });
    }

    private void finishWithdrawn(Player player, VaultTransferRepository.Transfer transfer) {
        storage.submit(() -> {
            transfers.transition(transfer.id(), "VAULT_REQUESTED", "VAULT_WITHDRAWN", null);
            ledger.transfer(transfer.idempotencyKey(), clearingAccount, transfer.targetAccount(), transfer.amountMinor(), "VAULT_DEPOSIT_TO_TREASURY", transfer.playerId());
            transfers.transition(transfer.id(), "VAULT_WITHDRAWN", "COMPLETED", null);
            return null;
        }).whenComplete((ignored, error) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (error != null) {
                plugin.getSLF4JLogger().error("Vault transfer {} requires recovery", transfer.id(), error);
                message(player, "Деньги списаны, но операция ожидает восстановления. ID: " + transfer.id(), NamedTextColor.RED);
            } else message(player, "Казначейство пополнено.", NamedTextColor.GREEN);
        }));
    }

    private void message(Player player, String text, NamedTextColor color) { if (player.isOnline()) player.sendMessage(Component.text(text, color)); }
    private String rootMessage(Throwable error) { Throwable value = error; while (value.getCause() != null) value = value.getCause(); return value.getMessage() == null ? value.getClass().getSimpleName() : value.getMessage(); }
}
