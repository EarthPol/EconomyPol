package com.earthpol.economyPol.economy.service.money;

import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

final class LiveMoneySnapshotService {

    private final DenominationService denominationService;
    private final PluginSettings.WalletSettings walletSettings;

    LiveMoneySnapshotService(DenominationService denominationService, PluginSettings.WalletSettings walletSettings) {
        this.denominationService = denominationService;
        this.walletSettings = walletSettings;
    }

    long scanPlayerMoney(Player player) {
        long total = 0L;
        if (walletSettings.includeLivePlayerInventory()) {
            total += countInventory(player.getInventory());
            total += denominationService.valueOf(player.getInventory().getItemInOffHand());
        }
        if (walletSettings.includeLiveEnderChest()) {
            total += countInventory(player.getEnderChest());
        }
        return total;
    }

    long countTopLevelEnderChest(Player player) {
        return countInventory(player.getEnderChest());
    }

    LiveMoneyService.LiveContainerSnapshot captureLiveContainerSnapshot(Player player) {
        ItemStack[] inventoryContents = cloneContents(player.getInventory().getContents());
        ItemStack[] enderContents = cloneContents(player.getEnderChest().getContents());
        ItemStack offHand = cloneStack(player.getInventory().getItemInOffHand());
        return new LiveMoneyService.LiveContainerSnapshot(inventoryContents, enderContents, offHand);
    }

    void restoreLiveContainerSnapshot(Player player, LiveMoneyService.LiveContainerSnapshot snapshot) {
        player.getInventory().setContents(cloneContents(snapshot.inventoryContents()));
        player.getEnderChest().setContents(cloneContents(snapshot.enderChestContents()));
        player.getInventory().setItemInOffHand(cloneStack(snapshot.offHand()));
    }

    LiveMoneySimulatedState simulatedStateFromSnapshot(LiveMoneyService.LiveContainerSnapshot snapshot) {
        return new LiveMoneySimulatedState(
                cloneContents(snapshot.inventoryContents()),
                cloneContents(snapshot.enderChestContents()),
                cloneStack(snapshot.offHand())
        );
    }

    long scanPlayerMoney(LiveMoneySimulatedState simulatedState) {
        long total = 0L;
        if (walletSettings.includeLivePlayerInventory()) {
            total += countContents(simulatedState.inventoryContents());
            total += denominationService.valueOf(simulatedState.offHand());
        }
        if (walletSettings.includeLiveEnderChest()) {
            total += countContents(simulatedState.enderChestContents());
        }
        return total;
    }

    long countContents(ItemStack[] contents) {
        long total = 0L;
        for (ItemStack itemStack : contents) {
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                continue;
            }
            total += denominationService.valueOf(itemStack);
        }
        return total;
    }

    ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] clone = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            clone[index] = cloneStack(contents[index]);
        }
        return clone;
    }

    ItemStack cloneStack(ItemStack itemStack) {
        return itemStack == null ? null : itemStack.clone();
    }

    private long countInventory(Inventory inventory) {
        long total = 0L;
        for (ItemStack itemStack : inventory.getContents()) {
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                continue;
            }
            total += denominationService.valueOf(itemStack);
        }
        return total;
    }
}


