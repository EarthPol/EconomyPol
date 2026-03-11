package com.earthpol.economyPol.domain;

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
