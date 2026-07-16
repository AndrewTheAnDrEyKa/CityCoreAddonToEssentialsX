package ru.citycore.economy;

import java.util.UUID;

public interface EconomyGateway {
    boolean available();
    long balanceMinor(UUID playerId);
    TransferResult withdraw(UUID playerId, long amountMinor);
    TransferResult deposit(UUID playerId, long amountMinor);

    record TransferResult(boolean successful, String message) {
        public static TransferResult ok() { return new TransferResult(true, "ok"); }
        public static TransferResult failed(String message) { return new TransferResult(false, message); }
    }
}

