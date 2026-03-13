package com.earthpol.economyPol.economy.service.money;

import org.bukkit.inventory.ItemStack;

final class LiveMoneySimulatedState {

    private final ItemStack[] inventoryContents;
    private final ItemStack[] enderChestContents;
    private ItemStack offHand;

    LiveMoneySimulatedState(ItemStack[] inventoryContents, ItemStack[] enderChestContents, ItemStack offHand) {
        this.inventoryContents = inventoryContents;
        this.enderChestContents = enderChestContents;
        this.offHand = offHand;
    }

    ItemStack[] inventoryContents() {
        return inventoryContents;
    }

    ItemStack[] enderChestContents() {
        return enderChestContents;
    }

    ItemStack offHand() {
        return offHand;
    }

    void setOffHand(ItemStack offHand) {
        this.offHand = offHand;
    }
}


