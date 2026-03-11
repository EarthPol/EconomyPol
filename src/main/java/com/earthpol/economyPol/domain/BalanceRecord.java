package com.earthpol.economyPol.domain;

public record BalanceRecord(long availableBalance, long reservedBalance) {

    public long spendable() {
        return availableBalance;
    }
}
