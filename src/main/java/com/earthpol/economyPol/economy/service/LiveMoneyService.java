package com.earthpol.economyPol.economy.service;

import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.Denomination;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;

public final class LiveMoneyService {

    public static final String NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE = "Not enough room to return change.";

    private final DenominationService denominationService;
    private final PluginSettings.WalletSettings walletSettings;

    public LiveMoneyService(DenominationService denominationService, PluginSettings.WalletSettings walletSettings) {
        this.denominationService = denominationService;
        this.walletSettings = walletSettings;
    }

    public long scanPlayerMoney(Player player) {
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

    public long countTopLevelEnderChest(Player player) {
        return countInventory(player.getEnderChest());
    }

    public ManagedEnderWalletSyncPlan planManagedEnderWalletSync(ItemStack[] currentContents, long targetBaseUnits) {
        ItemStack[] targetContents = cloneContents(currentContents);
        long existingTopLevelMoneyValue = 0L;
        boolean malformed = false;

        for (int slot = 0; slot < targetContents.length; slot++) {
            ItemStack itemStack = targetContents[slot];
            if (!denominationService.isMoney(itemStack)) {
                continue;
            }
            if (itemStack.getAmount() > itemStack.getMaxStackSize()) {
                malformed = true;
            }
            existingTopLevelMoneyValue += denominationService.valueOf(itemStack);
            targetContents[slot] = null;
        }

        List<ItemStack> pending = new ArrayList<>();
        for (ItemStack itemStack : denominationService.materialize(targetBaseUnits)) {
            pending.add(cloneStack(itemStack));
        }
        List<ItemStack> leftovers = placeIntoEmptySlots(targetContents, pending);
        long overflow = denominationService.countStacks(leftovers);
        return new ManagedEnderWalletSyncPlan(targetBaseUnits, existingTopLevelMoneyValue, overflow, malformed, targetContents);
    }

    public long removeFromLiveSources(Player player, long amount) {
        long remaining = amount;
        if (walletSettings.includeLivePlayerInventory()) {
            remaining = removeFromInventory(player.getInventory(), remaining, true);
        }
        if (remaining > 0L && walletSettings.includeLiveEnderChest()) {
            remaining = removeFromInventory(player.getEnderChest(), remaining, false);
        }
        return amount - remaining;
    }

    public SpendabilityResult canSpendFromLiveSources(
            Player player,
            long amount,
            List<MoneyRouteTarget> routingOrder,
            PluginSettings.ChangeOverflowPolicy changeOverflowPolicy
    ) {
        return canSpendFromSnapshot(captureLiveContainerSnapshot(player), amount, routingOrder, changeOverflowPolicy);
    }

    public SpendabilityResult canSpendFromSnapshot(
            LiveContainerSnapshot snapshot,
            long amount,
            List<MoneyRouteTarget> routingOrder,
            PluginSettings.ChangeOverflowPolicy changeOverflowPolicy
    ) {
        if (amount < 0L) {
            return SpendabilityResult.blocked("Cannot spend a negative amount.");
        }
        if (amount == 0L) {
            return SpendabilityResult.allowed();
        }

        SimulatedLiveState simulatedState = new SimulatedLiveState(
                cloneContents(snapshot.inventoryContents()),
                cloneContents(snapshot.enderChestContents()),
                cloneStack(snapshot.offHand())
        );
        long available = scanPlayerMoney(simulatedState);
        if (available < amount) {
            return SpendabilityResult.blocked("Insufficient funds.");
        }

        long removed = removeFromSimulatedSources(simulatedState, amount);
        if (removed == amount) {
            return SpendabilityResult.allowed();
        }

        long remaining = amount - removed;
        OverpayCandidate candidate = findSmallestOverpayCandidate(simulatedState, remaining);
        if (candidate == null) {
            return SpendabilityResult.blocked("Unable to make exact change from live funds.");
        }

        removeSingleCandidate(simulatedState, candidate);
        long debited = removed + candidate.denomination().baseUnits();
        long change = debited - amount;
        DeliveryResult changeDelivery = deliver(simulatedState, change, routingOrder);
        if (changeDelivery.remainder() > 0L && changeOverflowPolicy == PluginSettings.ChangeOverflowPolicy.FAIL) {
            return SpendabilityResult.blocked(NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE);
        }
        return SpendabilityResult.allowed();
    }

    public SpendResult spendFromLiveSources(
            Player player,
            long amount,
            List<MoneyRouteTarget> routingOrder,
            PluginSettings.ChangeOverflowPolicy changeOverflowPolicy
    ) {
        if (amount < 0L) {
            return SpendResult.failure(amount, "Cannot spend a negative amount.");
        }
        if (amount == 0L) {
            return SpendResult.success(0L, 0L, 0L);
        }

        long available = scanPlayerMoney(player);
        if (available < amount) {
            return SpendResult.failure(amount, "Insufficient funds.");
        }

        LiveContainerSnapshot snapshot = captureLiveContainerSnapshot(player);
        long removed = removeFromLiveSources(player, amount);
        if (removed == amount) {
            return SpendResult.success(amount, removed, 0L);
        }

        long remaining = amount - removed;
        OverpayCandidate candidate = findSmallestOverpayCandidate(player, remaining);
        if (candidate == null) {
            restoreLiveContainerSnapshot(player, snapshot);
            return SpendResult.failure(amount, "Unable to make exact change from live funds.");
        }

        removeSingleCandidate(player, candidate);
        long debited = removed + candidate.denomination().baseUnits();
        long change = debited - amount;
        DeliveryResult changeDelivery = deliver(player, change, routingOrder);
        if (changeDelivery.remainder() > 0L) {
            if (changeOverflowPolicy == PluginSettings.ChangeOverflowPolicy.FAIL) {
                restoreLiveContainerSnapshot(player, snapshot);
                return SpendResult.failure(amount, NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE);
            }
            return SpendResult.success(amount, debited, change, changeDelivery.remainder());
        }
        return SpendResult.success(amount, debited, change);
    }

    public DeliveryResult deliver(Player player, long amount, List<MoneyRouteTarget> routingOrder) {
        long remaining = amount;
        List<ItemStack> pending = denominationService.materialize(amount);
        long deliveredToInventory = 0L;
        long deliveredToEnder = 0L;

        for (MoneyRouteTarget target : routingOrder) {
            if (pending.isEmpty() || target == MoneyRouteTarget.CUSTODIAL_ACCOUNT) {
                break;
            }
            Inventory destination = target == MoneyRouteTarget.INVENTORY ? player.getInventory() : player.getEnderChest();
            List<ItemStack> leftovers = addToInventory(destination, pending);
            long deliveredNow = denominationService.countStacks(pending) - denominationService.countStacks(leftovers);
            if (target == MoneyRouteTarget.INVENTORY) {
                deliveredToInventory += deliveredNow;
            } else {
                deliveredToEnder += deliveredNow;
            }
            pending = leftovers;
            remaining = denominationService.countStacks(pending);
        }

        return new DeliveryResult(deliveredToInventory, deliveredToEnder, remaining);
    }

    private DeliveryResult deliver(SimulatedLiveState simulatedState, long amount, List<MoneyRouteTarget> routingOrder) {
        long remaining = amount;
        List<ItemStack> pending = denominationService.materialize(amount);
        long deliveredToInventory = 0L;
        long deliveredToEnder = 0L;

        for (MoneyRouteTarget target : routingOrder) {
            if (pending.isEmpty() || target == MoneyRouteTarget.CUSTODIAL_ACCOUNT) {
                break;
            }
            ItemStack[] destination = target == MoneyRouteTarget.INVENTORY
                    ? simulatedState.inventoryContents()
                    : simulatedState.enderChestContents();
            List<ItemStack> leftovers = addToContents(destination, pending);
            long deliveredNow = denominationService.countStacks(pending) - denominationService.countStacks(leftovers);
            if (target == MoneyRouteTarget.INVENTORY) {
                deliveredToInventory += deliveredNow;
            } else {
                deliveredToEnder += deliveredNow;
            }
            pending = leftovers;
            remaining = denominationService.countStacks(pending);
        }

        return new DeliveryResult(deliveredToInventory, deliveredToEnder, remaining);
    }

    public NormalizationResult normalizeEnderChest(Player player) {
        Inventory enderChest = player.getEnderChest();
        long total = 0L;
        boolean malformed = false;
        for (int slot = 0; slot < enderChest.getSize(); slot++) {
            ItemStack itemStack = enderChest.getItem(slot);
            if (!denominationService.isMoney(itemStack)) {
                continue;
            }
            if (itemStack.getAmount() > itemStack.getMaxStackSize()) {
                malformed = true;
            }
            total += denominationService.valueOf(itemStack);
            enderChest.setItem(slot, null);
        }

        List<ItemStack> leftovers = addToInventory(enderChest, denominationService.materialize(total));
        long overflow = denominationService.countStacks(leftovers);
        return new NormalizationResult(total, overflow, malformed);
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

    private long scanPlayerMoney(SimulatedLiveState simulatedState) {
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

    private long removeFromSimulatedSources(SimulatedLiveState simulatedState, long amount) {
        long remaining = amount;
        if (walletSettings.includeLivePlayerInventory()) {
            remaining = removeFromContents(simulatedState.inventoryContents(), remaining, simulatedState, true);
        }
        if (remaining > 0L && walletSettings.includeLiveEnderChest()) {
            remaining = removeFromContents(simulatedState.enderChestContents(), remaining, simulatedState, false);
        }
        return amount - remaining;
    }

    private long removeFromContents(ItemStack[] contents, long amount, SimulatedLiveState simulatedState, boolean includeOffhand) {
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

    private List<ItemStack> addToInventory(Inventory inventory, List<ItemStack> itemStacks) {
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack itemStack : itemStacks) {
            leftovers.addAll(inventory.addItem(itemStack).values());
        }
        return leftovers;
    }

    private List<ItemStack> addToContents(ItemStack[] contents, List<ItemStack> itemStacks) {
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack itemStack : itemStacks) {
            ItemStack pending = cloneStack(itemStack);
            if (pending == null || pending.getType() == Material.AIR) {
                continue;
            }
            for (int slot = 0; slot < contents.length && pending.getAmount() > 0; slot++) {
                ItemStack existing = contents[slot];
                if (existing == null || existing.getType() == Material.AIR || !existing.isSimilar(pending)) {
                    continue;
                }
                int space = existing.getMaxStackSize() - existing.getAmount();
                if (space <= 0) {
                    continue;
                }
                int moved = Math.min(space, pending.getAmount());
                existing.setAmount(existing.getAmount() + moved);
                pending.setAmount(pending.getAmount() - moved);
                contents[slot] = existing;
            }
            for (int slot = 0; slot < contents.length && pending.getAmount() > 0; slot++) {
                ItemStack existing = contents[slot];
                if (existing != null && existing.getType() != Material.AIR) {
                    continue;
                }
                contents[slot] = cloneStack(pending);
                pending.setAmount(0);
            }
            if (pending.getAmount() > 0) {
                leftovers.add(cloneStack(pending));
            }
        }
        return leftovers;
    }

    public LiveContainerSnapshot captureLiveContainerSnapshot(Player player) {
        ItemStack[] inventoryContents = cloneContents(player.getInventory().getContents());
        ItemStack[] enderContents = cloneContents(player.getEnderChest().getContents());
        ItemStack offHand = cloneStack(player.getInventory().getItemInOffHand());
        return new LiveContainerSnapshot(inventoryContents, enderContents, offHand);
    }

    public void restoreLiveContainerSnapshot(Player player, LiveContainerSnapshot snapshot) {
        player.getInventory().setContents(cloneContents(snapshot.inventoryContents()));
        player.getEnderChest().setContents(cloneContents(snapshot.enderChestContents()));
        player.getInventory().setItemInOffHand(cloneStack(snapshot.offHand()));
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

    private OverpayCandidate findSmallestOverpayCandidate(SimulatedLiveState simulatedState, long remaining) {
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

    private void removeSingleCandidate(SimulatedLiveState simulatedState, OverpayCandidate candidate) {
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

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] clone = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            clone[index] = cloneStack(contents[index]);
        }
        return clone;
    }

    private static ItemStack cloneStack(ItemStack itemStack) {
        return itemStack == null ? null : itemStack.clone();
    }

    private long countContents(ItemStack[] contents) {
        long total = 0L;
        for (ItemStack itemStack : contents) {
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                continue;
            }
            total += denominationService.valueOf(itemStack);
        }
        return total;
    }

    private List<ItemStack> placeIntoEmptySlots(ItemStack[] contents, List<ItemStack> itemStacks) {
        List<ItemStack> leftovers = new ArrayList<>();
        int nextItemIndex = 0;
        for (int slot = 0; slot < contents.length && nextItemIndex < itemStacks.size(); slot++) {
            if (contents[slot] != null && contents[slot].getType() != Material.AIR) {
                continue;
            }
            contents[slot] = cloneStack(itemStacks.get(nextItemIndex));
            nextItemIndex++;
        }
        for (int index = nextItemIndex; index < itemStacks.size(); index++) {
            leftovers.add(cloneStack(itemStacks.get(index)));
        }
        return leftovers;
    }

    public record DeliveryResult(long deliveredToInventory, long deliveredToEnder, long remainder) {}

    public record NormalizationResult(long normalizedValue, long overflow, boolean malformedStacksFound) {}

    public record ManagedEnderWalletSyncPlan(
            long targetBaseUnits,
            long existingTopLevelMoneyValue,
            long overflow,
            boolean malformedStacksFound,
            ItemStack[] targetContents
    ) {}

    public record SpendResult(
            boolean success,
            long requestedAmount,
            long debitedAmount,
            long changeAmount,
            long changeRoutedToCustodial,
            String message
    ) {

        public static SpendResult success(long requestedAmount, long debitedAmount, long changeAmount) {
            return new SpendResult(true, requestedAmount, debitedAmount, changeAmount, 0L, "Funds withdrawn.");
        }

        public static SpendResult success(long requestedAmount, long debitedAmount, long changeAmount, long changeRoutedToCustodial) {
            return new SpendResult(true, requestedAmount, debitedAmount, changeAmount, changeRoutedToCustodial, "Funds withdrawn.");
        }

        public static SpendResult failure(long requestedAmount, String message) {
            return new SpendResult(false, requestedAmount, 0L, 0L, 0L, message);
        }
    }

    public record LiveContainerSnapshot(ItemStack[] inventoryContents, ItemStack[] enderChestContents, ItemStack offHand) {}

    public record SpendabilityResult(boolean success, String message) {

        public static SpendabilityResult allowed() {
            return new SpendabilityResult(true, "Funds available.");
        }

        public static SpendabilityResult blocked(String message) {
            return new SpendabilityResult(false, message);
        }
    }

    private record OverpayCandidate(OverpaySourceType sourceType, int slot, Denomination denomination) {}

    private static final class SimulatedLiveState {

        private final ItemStack[] inventoryContents;
        private final ItemStack[] enderChestContents;
        private ItemStack offHand;

        private SimulatedLiveState(ItemStack[] inventoryContents, ItemStack[] enderChestContents, ItemStack offHand) {
            this.inventoryContents = inventoryContents;
            this.enderChestContents = enderChestContents;
            this.offHand = offHand;
        }

        private ItemStack[] inventoryContents() {
            return inventoryContents;
        }

        private ItemStack[] enderChestContents() {
            return enderChestContents;
        }

        private ItemStack offHand() {
            return offHand;
        }

        private void setOffHand(ItemStack offHand) {
            this.offHand = offHand;
        }
    }

    private enum OverpaySourceType {
        INVENTORY,
        ENDER_CHEST,
        OFFHAND
    }
}
