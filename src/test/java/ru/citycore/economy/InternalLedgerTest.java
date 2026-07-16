package ru.citycore.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InternalLedgerTest {
    @Test void hashIsStableSha256() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", InternalLedger.hash("abc"));
    }
    @Test void insufficientFundsCarriesExactMinorUnits() {
        var error = new InsufficientFundsException(12, 13);
        assertEquals(12, error.balanceMinor()); assertEquals(13, error.requestedMinor());
    }
}
