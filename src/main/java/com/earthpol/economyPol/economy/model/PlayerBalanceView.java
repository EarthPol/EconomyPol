package com.earthpol.economyPol.economy.model;

public record PlayerBalanceView(
        long custodialAvailable,
        long custodialReserved,
        long liveMoney,
        long frozenEnderWallet,
    boolean locked
) {

    public long spendable() {
        return liveMoney + frozenEnderWallet;
    }
}
