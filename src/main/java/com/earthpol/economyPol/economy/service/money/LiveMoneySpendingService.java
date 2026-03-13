package com.earthpol.economyPol.economy.service.money;

import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.Denomination;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.List;

final class LiveMoneySpendingService {

    private final DenominationService denominationService;
    private final PluginSettings.WalletSettings walletSettings;
    private final LiveMoneySnapshotService snapshotService;
    private final LiveMoneyDeliveryService deliveryService;

    LiveMoneySpendingService(
            DenominationService denominationService,
            PluginSettings.WalletSettings walletSettings,
            LiveMoneySnapshotService snapshotService,
            LiveMoneyDeliveryService deliveryService
    ) {
        this.denominationService = denominationService;
        this.walletSettings = walletSettings;
        this.snapshotService = snapshotService;
        this.deliveryService = deliveryService;
    }

    LiveMoneyService.SpendabilityResult canSpendFromLiveSources(
            Player player,
            long amount,
            List<MoneyRouteTarget> routingOrder,
            PluginSettings.ChangeOverflowPolicy changeOverflowPolicy
    ) {
        return canSpendFromSnapshot(snapshotService.captureLiveContainerSnapshot(player), amount, routingOrder, changeOverflowPolicy);
    }

    LiveMoneyService.SpendabilityResult canSpendFromSnapshot(
            LiveMoneyService.LiveContainerSnapshot snapshot,
            long amount,
            List<MoneyRouteTarget> routingOrder,
            PluginSettings.ChangeOverflowPolicy changeOverflowPolicy
    ) {
        if (amount < 0L) {
            return LiveMoneyService.SpendabilityResult.blocked("Cannot spend a negative amount.");
        }
        if (amount == 0L) {
            return LiveMoneyService.SpendabilityResult.allowed();
        }

        LiveMoneySimulatedState simulatedState = snapshotService.simulatedStateFromSnapshot(snapshot);
        long available = snapshotService.scanPlayerMoney(simulatedState);
        if (available < amount) {
            return LiveMoneyService.SpendabilityResult.blocked("Insufficient funds.");
        }

        long removed = removeFromSimulatedSources(simulatedState, amount);
        if (removed == amount) {
            return LiveMoneyService.SpendabilityResult.allowed();
        }

        long remaining = amount - removed;
        OverpayCandidate candidate = findSmallestOverpayCandidate(simulatedState, remaining);
        if (candidate == null) {
            return LiveMoneyService.SpendabilityResult.blocked("Unable to make exact change from live funds.");
        }

        removeSingleCandidate(simulatedState, candidate);
        long debited = removed + candidate.denomination().baseUnits();
        long change = debited - amount;
        LiveMoneyService.DeliveryResult changeDelivery = deliveryService.deliver(simulatedState, change, routingOrder);
        if (changeDelivery.remainder() > 0L && changeOverflowPolicy == PluginSettings.ChangeOverflowPolicy.FAIL) {
            return LiveMoneyService.SpendabilityResult.blocked(LiveMoneyService.NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE);
        }
        return LiveMoneyService.SpendabilityResult.allowed();
    }

    LiveMoneyService.SpendResult spendFromLiveSources(
            Player player,
            long amount,
            List<MoneyRouteTarget> routingOrder,
            PluginSettings.ChangeOverflowPolicy changeOverflowPolicy
    ) {
        if (amount < 0L) {
            return LiveMoneyService.SpendResult.failure(amount, "Cannot spend a negative amount.");
        }
        if (amount == 0L) {
            return LiveMoneyService.SpendResult.success(0L, 0L, 0L);
        }

        long available = snapshotService.scanPlayerMoney(player);
        if (available < amount) {
            return LiveMoneyService.SpendResult.failure(amount, "Insufficient funds.");
        }

        LiveMoneyService.LiveContainerSnapshot snapshot = snapshotService.captureLiveContainerSnapshot(player);
        long removed = removeFromLiveSources(player, amount);
        if (removed == amount) {
            return LiveMoneyService.SpendResult.success(amount, removed, 0L);
        }

        long remaining = amount - removed;
        OverpayCandidate candidate = findSmallestOverpayCandidate(player, remaining);
        if (candidate == null) {
            snapshotService.restoreLiveContainerSnapshot(player, snapshot);
            return LiveMoneyService.SpendResult.failure(amount, "Unable to make exact change from live funds.");
        }

        removeSingleCandidate(player, candidate);
        long debited = removed + candidate.denomination().baseUnits();
        long change = debited - amount;
        LiveMoneyService.DeliveryResult changeDelivery = deliveryService.deliver(player, change, routingOrder);
        if (changeDelivery.remainder() > 0L) {
            if (changeOverflowPolicy == PluginSettings.ChangeOverflowPolicy.FAIL) {
                snapshotService.restoreLiveContainerSnapshot(player, snapshot);
                return LiveMoneyService.SpendResult.failure(amount, LiveMoneyService.NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE);
            }
            return LiveMoneyService.SpendResult.success(amount, debited, change, changeDelivery.remainder());
        }
        return LiveMoneyService.SpendResult.success(amount, debited, change);
    }

    long removeFromLiveSources(Player player, long amount) {
        long remaining = amount;
        if (walletSettings.includeLivePlayerInventory()) {
            remaining = removeFromInventory(player.getInventory(), remaining, true);
        }
        if (remaining > 0L && walletSettings.includeLiveEnderChest()) {
            remaining = removeFromInventory(player.getEnderChest(), remaining, false);
        }
        return amount - remaining;
    }

    private long removeFromInventory(Inventory inventory, long amount, boolean includeOffhand) {
        long remaining = amount;
        for (Denomination denomination : denominationService.descending()) {
            remaining = removeMaterial(inventory, denomination, remaining);
            if (remaining <= 0L) {
                return 0L;
            }
        }
        if (includeOffhand && inventory instanceof PlayerInventory playerInventory && remaining > 0L) {
            ItemStack offHand = playerInventory.getItemInOffHand();
            if (denominationService.isMoney(offHand)) {
                Denomination denomination = denominationService.find(offHand.getType()).orElseThrow();
                long neededItems = remaining / denomination.baseUnits();
                if (neededItems > 0L) {
                    int remove = (int) Math.min(offHand.getAmount(), neededItems);
                    offHand.setAmount(offHand.getAmount() - remove);
                    if (offHand.getAmount() <= 0) {
                        playerInventory.setItemInOffHand(null);
                    } else {
                        playerInventory.setItemInOffHand(offHand);
                    }
                    remaining -= remove * denomination.baseUnits();
                }
            }
        }
        return remaining;
    }

    private long removeFromSimulatedSources(LiveMoneySimulatedState simulatedState, long amount) {
        long remaining = amount;
        if (walletSettings.includeLivePlayerInventory()) {
            remaining = removeFromContents(simulatedState.inventoryContents(), remaining, simulatedState, true);
        }
        if (remaining > 0L && walletSettings.includeLiveEnderChest()) {
            remaining = removeFromContents(simulatedState.enderChestContents(), remaining, simulatedState, false);
        }
        return amount - remaining;
    }

    private long removeFromContents(ItemStack[] contents, long amount, LiveMoneySimulatedState simulatedState, boolean includeOffhand) {
        long remaining = amount;
        for (Denomination denomination : denominationService.descending()) {
            remaining = removeMaterial(contents, denomination, remaining);
            if (remaining <= 0L) {
                return 0L;
            }
        }
        if (includeOffhand && remaining > 0L && denominationService.isMoney(simulatedState.offHand())) {
            Denomination denomination = denominationService.find(simulatedState.offHand().getType()).orElseThrow();
            long neededItems = remaining / denomination.baseUnits();
            if (neededItems > 0L) {
                int remove = (int) Math.min(simulatedState.offHand().getAmount(), neededItems);
                simulatedState.offHand().setAmount(simulatedState.offHand().getAmount() - remove);
                if (simulatedState.offHand().getAmount() <= 0) {
                    simulatedState.setOffHand(null);
                }
                remaining -= remove * denomination.baseUnits();
            }
        }
        return remaining;
    }

    private long removeMaterial(Inventory inventory, Denomination denomination, long remaining) {
        if (remaining < denomination.baseUnits()) {
            return remaining;
        }
        for (int slot = 0; slot < inventory.getSize() && remaining >= denomination.baseUnits(); slot++) {
            ItemStack itemStack = inventory.getItem(slot);
            if (itemStack == null || itemStack.getType() != denomination.material()) {
                continue;
            }
            long neededItems = remaining / denomination.baseUnits();
            int remove = (int) Math.min(itemStack.getAmount(), neededItems);
            if (remove <= 0) {
                continue;
            }
            itemStack.setAmount(itemStack.getAmount() - remove);
            if (itemStack.getAmount() <= 0) {
                inventory.setItem(slot, null);
            } else {
                inventory.setItem(slot, itemStack);
            }
            remaining -= remove * denomination.baseUnits();
        }
        return remaining;
    }

    private long removeMaterial(ItemStack[] contents, Denomination denomination, long remaining) {
        if (remaining < denomination.baseUnits()) {
            return remaining;
        }
        for (int slot = 0; slot < contents.length && remaining >= denomination.baseUnits(); slot++) {
            ItemStack itemStack = contents[slot];
            if (itemStack == null || itemStack.getType() != denomination.material()) {
                continue;
            }
            long neededItems = remaining / denomination.baseUnits();
            int remove = (int) Math.min(itemStack.getAmount(), neededItems);
            if (remove <= 0) {
                continue;
            }
            itemStack.setAmount(itemStack.getAmount() - remove);
            if (itemStack.getAmount() <= 0) {
                contents[slot] = null;
            } else {
                contents[slot] = itemStack;
            }
            remaining -= remove * denomination.baseUnits();
        }
        return remaining;
    }

    private OverpayCandidate findSmallestOverpayCandidate(Player player, long remaining) {
        OverpayCandidate best = null;
        if (walletSettings.includeLivePlayerInventory()) {
            best = findSmallestOverpayCandidate(player.getInventory(), remaining, OverpaySourceType.INVENTORY, best);
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (denominationService.isMoney(offHand)) {
                Denomination denomination = denominationService.find(offHand.getType()).orElseThrow();
                if (denomination.baseUnits() > remaining && (best == null || denomination.baseUnits() < best.denomination().baseUnits())) {
                    best = new OverpayCandidate(OverpaySourceType.OFFHAND, -1, denomination);
                }
            }
        }
        if (walletSettings.includeLiveEnderChest()) {
            best = findSmallestOverpayCandidate(player.getEnderChest(), remaining, OverpaySourceType.ENDER_CHEST, best);
        }
        return best;
    }

    private OverpayCandidate findSmallestOverpayCandidate(LiveMoneySimulatedState simulatedState, long remaining) {
        OverpayCandidate best = null;
        if (walletSettings.includeLivePlayerInventory()) {
            best = findSmallestOverpayCandidate(simulatedState.inventoryContents(), remaining, OverpaySourceType.INVENTORY, best);
            ItemStack offHand = simulatedState.offHand();
            if (denominationService.isMoney(offHand)) {
                Denomination denomination = denominationService.find(offHand.getType()).orElseThrow();
                if (denomination.baseUnits() > remaining && (best == null || denomination.baseUnits() < best.denomination().baseUnits())) {
                    best = new OverpayCandidate(OverpaySourceType.OFFHAND, -1, denomination);
                }
            }
        }
        if (walletSettings.includeLiveEnderChest()) {
            best = findSmallestOverpayCandidate(simulatedState.enderChestContents(), remaining, OverpaySourceType.ENDER_CHEST, best);
        }
        return best;
    }

    private OverpayCandidate findSmallestOverpayCandidate(
            Inventory inventory,
            long remaining,
            OverpaySourceType sourceType,
            OverpayCandidate currentBest
    ) {
        OverpayCandidate best = currentBest;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack itemStack = inventory.getItem(slot);
            if (!denominationService.isMoney(itemStack)) {
                continue;
            }
            Denomination denomination = denominationService.find(itemStack.getType()).orElseThrow();
            if (denomination.baseUnits() <= remaining) {
                continue;
            }
            if (best == null || denomination.baseUnits() < best.denomination().baseUnits()) {
                best = new OverpayCandidate(sourceType, slot, denomination);
            }
        }
        return best;
    }

    private OverpayCandidate findSmallestOverpayCandidate(
            ItemStack[] contents,
            long remaining,
            OverpaySourceType sourceType,
            OverpayCandidate currentBest
    ) {
        OverpayCandidate best = currentBest;
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack itemStack = contents[slot];
            if (!denominationService.isMoney(itemStack)) {
                continue;
            }
            Denomination denomination = denominationService.find(itemStack.getType()).orElseThrow();
            if (denomination.baseUnits() <= remaining) {
                continue;
            }
            if (best == null || denomination.baseUnits() < best.denomination().baseUnits()) {
                best = new OverpayCandidate(sourceType, slot, denomination);
            }
        }
        return best;
    }

    private void removeSingleCandidate(Player player, OverpayCandidate candidate) {
        switch (candidate.sourceType()) {
            case INVENTORY -> decrementItem(player.getInventory(), candidate.slot());
            case ENDER_CHEST -> decrementItem(player.getEnderChest(), candidate.slot());
            case OFFHAND -> {
                ItemStack offHand = player.getInventory().getItemInOffHand();
                if (offHand == null || offHand.getType().isAir()) {
                    throw new IllegalStateException("Expected money item in offhand for change-making.");
                }
                offHand.setAmount(offHand.getAmount() - 1);
                if (offHand.getAmount() <= 0) {
                    player.getInventory().setItemInOffHand(null);
                } else {
                    player.getInventory().setItemInOffHand(offHand);
                }
            }
        }
    }

    private void removeSingleCandidate(LiveMoneySimulatedState simulatedState, OverpayCandidate candidate) {
        switch (candidate.sourceType()) {
            case INVENTORY -> decrementItem(simulatedState.inventoryContents(), candidate.slot());
            case ENDER_CHEST -> decrementItem(simulatedState.enderChestContents(), candidate.slot());
            case OFFHAND -> {
                ItemStack offHand = simulatedState.offHand();
                if (offHand == null || offHand.getType().isAir()) {
                    throw new IllegalStateException("Expected money item in offhand for change-making.");
                }
                offHand.setAmount(offHand.getAmount() - 1);
                if (offHand.getAmount() <= 0) {
                    simulatedState.setOffHand(null);
                }
            }
        }
    }

    private void decrementItem(Inventory inventory, int slot) {
        ItemStack itemStack = inventory.getItem(slot);
        if (itemStack == null || itemStack.getType().isAir()) {
            throw new IllegalStateException("Expected money item in slot " + slot + " for change-making.");
        }
        itemStack.setAmount(itemStack.getAmount() - 1);
        if (itemStack.getAmount() <= 0) {
            inventory.setItem(slot, null);
        } else {
            inventory.setItem(slot, itemStack);
        }
    }

    private void decrementItem(ItemStack[] contents, int slot) {
        ItemStack itemStack = contents[slot];
        if (itemStack == null || itemStack.getType().isAir()) {
            throw new IllegalStateException("Expected money item in slot " + slot + " for change-making.");
        }
        itemStack.setAmount(itemStack.getAmount() - 1);
        if (itemStack.getAmount() <= 0) {
            contents[slot] = null;
        } else {
            contents[slot] = itemStack;
        }
    }

    private record OverpayCandidate(OverpaySourceType sourceType, int slot, Denomination denomination) {}

    private enum OverpaySourceType {
        INVENTORY,
        ENDER_CHEST,
        OFFHAND
    }
}


