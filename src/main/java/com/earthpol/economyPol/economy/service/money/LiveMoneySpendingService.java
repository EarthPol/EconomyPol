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
import java.util.function.Supplier;

final class LiveMoneySpendingService {

    private final DenominationService denominationService;
    private final Supplier<PluginSettings.WalletSettings> walletSettingsSupplier;
    private final LiveMoneySnapshotService snapshotService;
    private final LiveMoneyDeliveryService deliveryService;
    private final ShulkerDeliveryService shulkerDeliveryService;

    LiveMoneySpendingService(
            DenominationService denominationService,
            Supplier<PluginSettings.WalletSettings> walletSettingsSupplier,
            LiveMoneySnapshotService snapshotService,
            LiveMoneyDeliveryService deliveryService,
            ShulkerDeliveryService shulkerDeliveryService
    ) {
        this.denominationService = denominationService;
        this.walletSettingsSupplier = walletSettingsSupplier;
        this.snapshotService = snapshotService;
        this.deliveryService = deliveryService;
        this.shulkerDeliveryService = shulkerDeliveryService;
    }

    LiveMoneyService.SpendabilityResult canSpendFromLiveSources(
            Player player,
            long amount,
            List<MoneyRouteTarget> routingOrder,
            PluginSettings.ChangeOverflowPolicy changeOverflowPolicy
    ) {
        return canSpendFromLiveSources(player, amount, routingOrder, changeOverflowPolicy, true);
    }

    LiveMoneyService.SpendabilityResult canSpendFromLiveSources(
            Player player,
            long amount,
            List<MoneyRouteTarget> routingOrder,
            PluginSettings.ChangeOverflowPolicy changeOverflowPolicy,
            boolean allowShulkerDelivery
    ) {
        return canSpendFromSnapshot(
                snapshotService.captureLiveContainerSnapshot(player),
                amount,
                routingOrder,
                changeOverflowPolicy,
                allowShulkerDelivery
        );
    }

    LiveMoneyService.SpendabilityResult canSpendFromSnapshot(
            LiveMoneyService.LiveContainerSnapshot snapshot,
            long amount,
            List<MoneyRouteTarget> routingOrder,
            PluginSettings.ChangeOverflowPolicy changeOverflowPolicy
    ) {
        return canSpendFromSnapshot(snapshot, amount, routingOrder, changeOverflowPolicy, true);
    }

    LiveMoneyService.SpendabilityResult canSpendFromSnapshot(
            LiveMoneyService.LiveContainerSnapshot snapshot,
            long amount,
            List<MoneyRouteTarget> routingOrder,
            PluginSettings.ChangeOverflowPolicy changeOverflowPolicy,
            boolean allowShulkerDelivery
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
        LiveMoneyService.DeliveryResult changeDelivery =
                deliveryService.deliver(simulatedState, change, routingOrder, allowShulkerDelivery);
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
        return spendFromLiveSources(player, amount, routingOrder, changeOverflowPolicy, true);
    }

    LiveMoneyService.SpendResult spendFromLiveSources(
            Player player,
            long amount,
            List<MoneyRouteTarget> routingOrder,
            PluginSettings.ChangeOverflowPolicy changeOverflowPolicy,
            boolean allowShulkerDelivery
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
        LiveMoneyService.DeliveryResult changeDelivery =
                deliveryService.deliver(player, change, routingOrder, allowShulkerDelivery);
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
        PluginSettings.WalletSettings walletSettings = walletSettingsSupplier.get();
        long remaining = amount;
        if (walletSettings.includeLivePlayerInventory()) {
            remaining = removeFromPlayerInventory(player.getInventory(), remaining);
        }
        if (remaining > 0L && walletSettings.includeLiveEnderChest()) {
            remaining = removeFromInventory(player.getEnderChest(), remaining, false);
        }
        return amount - remaining;
    }

    private long removeFromInventory(Inventory inventory, long amount, boolean includeOffhand) {
        long remaining = amount;
        remaining -= shulkerDeliveryService.removeFromTopLevelGoldShulkers(inventory, remaining);
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

    private long removeFromPlayerInventory(PlayerInventory inventory, long amount) {
        long remaining = amount;

        ItemStack[] storageContents = inventory.getStorageContents();
        remaining -= shulkerDeliveryService.removeFromTopLevelGoldShulkers(storageContents, remaining);
        inventory.setStorageContents(storageContents);

        ItemStack[] offHandContents = new ItemStack[] {inventory.getItemInOffHand()};
        remaining -= shulkerDeliveryService.removeFromTopLevelGoldShulkers(offHandContents, remaining);
        inventory.setItemInOffHand(offHandContents[0]);

        for (Denomination denomination : denominationService.descending()) {
            remaining = removeMaterial(storageContents, denomination, remaining);
            if (remaining <= 0L) {
                inventory.setStorageContents(storageContents);
                return 0L;
            }
        }
        inventory.setStorageContents(storageContents);

        if (remaining > 0L) {
            ItemStack offHand = inventory.getItemInOffHand();
            if (denominationService.isMoney(offHand)) {
                Denomination denomination = denominationService.find(offHand.getType()).orElseThrow();
                long neededItems = remaining / denomination.baseUnits();
                if (neededItems > 0L) {
                    int remove = (int) Math.min(offHand.getAmount(), neededItems);
                    offHand.setAmount(offHand.getAmount() - remove);
                    if (offHand.getAmount() <= 0) {
                        inventory.setItemInOffHand(null);
                    } else {
                        inventory.setItemInOffHand(offHand);
                    }
                    remaining -= remove * denomination.baseUnits();
                }
            }
        }
        return remaining;
    }

    private long removeFromSimulatedSources(LiveMoneySimulatedState simulatedState, long amount) {
        PluginSettings.WalletSettings walletSettings = walletSettingsSupplier.get();
        long remaining = amount;
        if (walletSettings.includeLivePlayerInventory()) {
            remaining = removeFromSimulatedInventory(simulatedState, remaining);
        }
        if (remaining > 0L && walletSettings.includeLiveEnderChest()) {
            remaining = removeFromContents(simulatedState.enderChestContents(), remaining, simulatedState, false);
        }
        return amount - remaining;
    }

    private long removeFromSimulatedInventory(LiveMoneySimulatedState simulatedState, long amount) {
        long remaining = amount;
        remaining -= shulkerDeliveryService.removeFromTopLevelGoldShulkers(simulatedState.inventoryContents(), remaining);

        ItemStack[] offHandContents = new ItemStack[] {simulatedState.offHand()};
        remaining -= shulkerDeliveryService.removeFromTopLevelGoldShulkers(offHandContents, remaining);
        simulatedState.setOffHand(offHandContents[0]);

        for (Denomination denomination : denominationService.descending()) {
            remaining = removeMaterial(simulatedState.inventoryContents(), denomination, remaining);
            if (remaining <= 0L) {
                return 0L;
            }
        }

        if (remaining > 0L && denominationService.isMoney(simulatedState.offHand())) {
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

    private long removeFromContents(ItemStack[] contents, long amount, LiveMoneySimulatedState simulatedState, boolean includeOffhand) {
        long remaining = amount;
        remaining -= shulkerDeliveryService.removeFromTopLevelGoldShulkers(contents, remaining);
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
        PluginSettings.WalletSettings walletSettings = walletSettingsSupplier.get();
        OverpayCandidate best = null;
        if (walletSettings.includeLivePlayerInventory()) {
            best = findSmallestOverpayCandidateInGoldShulkers(
                    player.getInventory().getStorageContents(),
                    remaining,
                    OverpaySourceType.INVENTORY_SHULKER,
                    best
            );
            best = findSmallestOverpayCandidateInOffHandGoldShulker(player.getInventory().getItemInOffHand(), remaining, best);
            best = findSmallestOverpayCandidate(player.getInventory().getStorageContents(), remaining, OverpaySourceType.INVENTORY, best);
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (denominationService.isMoney(offHand)) {
                Denomination denomination = denominationService.find(offHand.getType()).orElseThrow();
                if (denomination.baseUnits() > remaining && (best == null || denomination.baseUnits() < best.denomination().baseUnits())) {
                    best = new OverpayCandidate(OverpaySourceType.OFFHAND, -1, -1, denomination);
                }
            }
        }
        if (walletSettings.includeLiveEnderChest()) {
            best = findSmallestOverpayCandidateInGoldShulkers(player.getEnderChest(), remaining, OverpaySourceType.ENDER_CHEST_SHULKER, best);
            best = findSmallestOverpayCandidate(player.getEnderChest(), remaining, OverpaySourceType.ENDER_CHEST, best);
        }
        return best;
    }

    private OverpayCandidate findSmallestOverpayCandidate(LiveMoneySimulatedState simulatedState, long remaining) {
        PluginSettings.WalletSettings walletSettings = walletSettingsSupplier.get();
        OverpayCandidate best = null;
        if (walletSettings.includeLivePlayerInventory()) {
            best = findSmallestOverpayCandidateInGoldShulkers(simulatedState.inventoryContents(), remaining, OverpaySourceType.INVENTORY_SHULKER, best);
            best = findSmallestOverpayCandidateInOffHandGoldShulker(simulatedState.offHand(), remaining, best);
            best = findSmallestOverpayCandidate(simulatedState.inventoryContents(), remaining, OverpaySourceType.INVENTORY, best);
            ItemStack offHand = simulatedState.offHand();
            if (denominationService.isMoney(offHand)) {
                Denomination denomination = denominationService.find(offHand.getType()).orElseThrow();
                if (denomination.baseUnits() > remaining && (best == null || denomination.baseUnits() < best.denomination().baseUnits())) {
                    best = new OverpayCandidate(OverpaySourceType.OFFHAND, -1, -1, denomination);
                }
            }
        }
        if (walletSettings.includeLiveEnderChest()) {
            best = findSmallestOverpayCandidateInGoldShulkers(simulatedState.enderChestContents(), remaining, OverpaySourceType.ENDER_CHEST_SHULKER, best);
            best = findSmallestOverpayCandidate(simulatedState.enderChestContents(), remaining, OverpaySourceType.ENDER_CHEST, best);
        }
        return best;
    }

    private OverpayCandidate findSmallestOverpayCandidateInOffHandGoldShulker(
            ItemStack offHand,
            long remaining,
            OverpayCandidate currentBest
    ) {
        ShulkerDeliveryService.NestedMoneyReference best =
                shulkerDeliveryService.findSmallestOverpayCandidateInTopLevelGoldShulkers(
                        new ItemStack[] {offHand},
                        remaining,
                        currentBest == null || currentBest.sourceType() != OverpaySourceType.OFFHAND_SHULKER
                                ? null
                                : new ShulkerDeliveryService.NestedMoneyReference(
                                        0,
                                        currentBest.innerSlot(),
                                        currentBest.denomination()
                                )
                );
        if (best == null) {
            return currentBest;
        }
        OverpayCandidate candidate = new OverpayCandidate(
                OverpaySourceType.OFFHAND_SHULKER,
                -1,
                best.innerSlot(),
                best.denomination()
        );
        return isBetterCandidate(candidate, currentBest) ? candidate : currentBest;
    }

    private OverpayCandidate findSmallestOverpayCandidateInGoldShulkers(
            Inventory inventory,
            long remaining,
            OverpaySourceType sourceType,
            OverpayCandidate currentBest
    ) {
        ShulkerDeliveryService.NestedMoneyReference best =
                shulkerDeliveryService.findSmallestOverpayCandidateInTopLevelGoldShulkers(
                        inventory,
                        remaining,
                        currentBest == null || !currentBest.sourceType().isShulker()
                                ? null
                                : new ShulkerDeliveryService.NestedMoneyReference(
                                        currentBest.outerSlot(),
                                        currentBest.innerSlot(),
                                        currentBest.denomination()
                                )
                );
        if (best == null) {
            return currentBest;
        }
        OverpayCandidate candidate = new OverpayCandidate(
                sourceType,
                best.outerSlot(),
                best.innerSlot(),
                best.denomination()
        );
        return isBetterCandidate(candidate, currentBest) ? candidate : currentBest;
    }

    private OverpayCandidate findSmallestOverpayCandidateInGoldShulkers(
            ItemStack[] contents,
            long remaining,
            OverpaySourceType sourceType,
            OverpayCandidate currentBest
    ) {
        ShulkerDeliveryService.NestedMoneyReference best =
                shulkerDeliveryService.findSmallestOverpayCandidateInTopLevelGoldShulkers(
                        contents,
                        remaining,
                        currentBest == null || !currentBest.sourceType().isShulker()
                                ? null
                                : new ShulkerDeliveryService.NestedMoneyReference(
                                        currentBest.outerSlot(),
                                        currentBest.innerSlot(),
                                        currentBest.denomination()
                                )
                );
        if (best == null) {
            return currentBest;
        }
        OverpayCandidate candidate = new OverpayCandidate(
                sourceType,
                best.outerSlot(),
                best.innerSlot(),
                best.denomination()
        );
        return isBetterCandidate(candidate, currentBest) ? candidate : currentBest;
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
            OverpayCandidate candidate = new OverpayCandidate(sourceType, slot, -1, denomination);
            if (isBetterCandidate(candidate, best)) {
                best = candidate;
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
            OverpayCandidate candidate = new OverpayCandidate(sourceType, slot, -1, denomination);
            if (isBetterCandidate(candidate, best)) {
                best = candidate;
            }
        }
        return best;
    }

    private void removeSingleCandidate(Player player, OverpayCandidate candidate) {
        switch (candidate.sourceType()) {
            case INVENTORY_SHULKER ->
                    shulkerDeliveryService.decrementNestedItem(player.getInventory(), candidate.outerSlot(), candidate.innerSlot());
            case INVENTORY -> decrementItem(player.getInventory(), candidate.outerSlot());
            case OFFHAND_SHULKER -> {
                ItemStack[] offHandContents = new ItemStack[] {player.getInventory().getItemInOffHand()};
                shulkerDeliveryService.decrementNestedItem(offHandContents, 0, candidate.innerSlot());
                player.getInventory().setItemInOffHand(offHandContents[0]);
            }
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
            case ENDER_CHEST_SHULKER ->
                    shulkerDeliveryService.decrementNestedItem(player.getEnderChest(), candidate.outerSlot(), candidate.innerSlot());
            case ENDER_CHEST -> decrementItem(player.getEnderChest(), candidate.outerSlot());
        }
    }

    private void removeSingleCandidate(LiveMoneySimulatedState simulatedState, OverpayCandidate candidate) {
        switch (candidate.sourceType()) {
            case INVENTORY_SHULKER ->
                    shulkerDeliveryService.decrementNestedItem(
                            simulatedState.inventoryContents(),
                            candidate.outerSlot(),
                            candidate.innerSlot()
                    );
            case INVENTORY -> decrementItem(simulatedState.inventoryContents(), candidate.outerSlot());
            case OFFHAND_SHULKER -> {
                ItemStack[] offHandContents = new ItemStack[] {simulatedState.offHand()};
                shulkerDeliveryService.decrementNestedItem(offHandContents, 0, candidate.innerSlot());
                simulatedState.setOffHand(offHandContents[0]);
            }
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
            case ENDER_CHEST_SHULKER ->
                    shulkerDeliveryService.decrementNestedItem(
                            simulatedState.enderChestContents(),
                            candidate.outerSlot(),
                            candidate.innerSlot()
                    );
            case ENDER_CHEST -> decrementItem(simulatedState.enderChestContents(), candidate.outerSlot());
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

    private boolean isBetterCandidate(OverpayCandidate candidate, OverpayCandidate currentBest) {
        return currentBest == null || candidate.denomination().baseUnits() < currentBest.denomination().baseUnits();
    }

    private record OverpayCandidate(
            OverpaySourceType sourceType,
            int outerSlot,
            int innerSlot,
            Denomination denomination
    ) {}

    private enum OverpaySourceType {
        INVENTORY_SHULKER,
        INVENTORY,
        OFFHAND_SHULKER,
        ENDER_CHEST,
        ENDER_CHEST_SHULKER,
        OFFHAND;

        boolean isShulker() {
            return this == INVENTORY_SHULKER || this == OFFHAND_SHULKER || this == ENDER_CHEST_SHULKER;
        }
    }
}
