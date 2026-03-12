package com.earthpol.economyPol.model;

public record BalanceRecord(long availableBalance, long reservedBalance) {

    public long spendable() {
        return availableBalance;
    }
}
