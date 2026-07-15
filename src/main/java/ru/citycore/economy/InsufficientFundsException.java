package ru.citycore.economy;

public final class InsufficientFundsException extends RuntimeException {
    private final long balanceMinor; private final long requestedMinor;
    public InsufficientFundsException(long balanceMinor, long requestedMinor) {
        super("Недостаточно средств: доступно " + balanceMinor + ", требуется " + requestedMinor);
        this.balanceMinor = balanceMinor; this.requestedMinor = requestedMinor;
    }
    public long balanceMinor() { return balanceMinor; }
    public long requestedMinor() { return requestedMinor; }
}

