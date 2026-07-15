package ru.citycore.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

public final class VaultEconomyGateway implements EconomyGateway {
    private final Economy economy;
    private final int scale;
    public VaultEconomyGateway(Economy economy, int scale) { this.economy = economy; this.scale = scale; }
    @Override public boolean available() { return economy != null; }
    @Override public long balanceMinor(UUID id) { return toMinor(economy.getBalance(player(id))); }
    @Override public TransferResult withdraw(UUID id, long amount) {
        var response = economy.withdrawPlayer(player(id), toMajor(amount));
        return response.transactionSuccess() ? TransferResult.ok() : TransferResult.failed(response.errorMessage);
    }
    @Override public TransferResult deposit(UUID id, long amount) {
        var response = economy.depositPlayer(player(id), toMajor(amount));
        return response.transactionSuccess() ? TransferResult.ok() : TransferResult.failed(response.errorMessage);
    }
    private OfflinePlayer player(UUID id) { return Bukkit.getOfflinePlayer(id); }
    private long toMinor(double value) { return BigDecimal.valueOf(value).movePointRight(scale).setScale(0, RoundingMode.HALF_EVEN).longValueExact(); }
    private double toMajor(long value) { return BigDecimal.valueOf(value, scale).doubleValue(); }
}

