package com.earthpol.economyPol.economy.model;

public record BalanceRecord(long availableBalance, long reservedBalance) {

    public long spendable() {
        return availableBalance;
    }
}
