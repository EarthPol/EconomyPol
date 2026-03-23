package com.earthpol.economyPol.economy.model;

public record PlayerBalanceView(
        long custodialAvailable,
        long custodialReserved,
        long inventoryMoney,
        long enderChestMoney,
        long frozenEnderWallet,
        boolean locked
) {

    public long liveMoney() {
        return inventoryMoney + enderChestMoney;
    }

    public long spendable() {
        return liveMoney() + frozenEnderWallet;
    }
}
