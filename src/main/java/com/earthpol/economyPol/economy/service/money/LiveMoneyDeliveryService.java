package com.earthpol.economyPol.economy.service.money;

import com.earthpol.economyPol.economy.model.Denomination;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

final class LiveMoneyDeliveryService {

    private final DenominationService denominationService;
    private final LiveMoneySnapshotService snapshotService;

    LiveMoneyDeliveryService(DenominationService denominationService, LiveMoneySnapshotService snapshotService) {
        this.denominationService = denominationService;
        this.snapshotService = snapshotService;
    }

    LiveMoneyService.DeliveryResult deliver(Player player, long amount, List<MoneyRouteTarget> routingOrder) {
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

        return new LiveMoneyService.DeliveryResult(deliveredToInventory, deliveredToEnder, remaining);
    }

    long maxDeliverableToInventory(LiveMoneyService.LiveContainerSnapshot snapshot, long maxAmount) {
        if (maxAmount <= 0L) {
            return 0L;
        }

        List<Denomination> denominations = denominationService.descending();
        long[] existingStackCapacity = new long[denominations.size()];
        int emptySlots = 0;
        for (ItemStack itemStack : snapshot.inventoryContents()) {
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                emptySlots++;
                continue;
            }
            for (int index = 0; index < denominations.size(); index++) {
                Denomination denomination = denominations.get(index);
                if (denomination.material() != itemStack.getType()) {
                    continue;
                }
                int freeItems = itemStack.getMaxStackSize() - itemStack.getAmount();
                if (freeItems > 0) {
                    existingStackCapacity[index] += freeItems;
                }
                break;
            }
        }
        return maxDeliverableToInventory(
                denominations,
                existingStackCapacity,
                emptySlots,
                Math.max(0L, maxAmount),
                0
        );
    }

    LiveMoneyService.DeliveryResult deliver(LiveMoneySimulatedState simulatedState, long amount, List<MoneyRouteTarget> routingOrder) {
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

        return new LiveMoneyService.DeliveryResult(deliveredToInventory, deliveredToEnder, remaining);
    }

    LiveMoneyService.ManagedEnderWalletSyncPlan planManagedEnderWalletSync(ItemStack[] currentContents, long targetBaseUnits) {
        ItemStack[] targetContents = snapshotService.cloneContents(currentContents);
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
            pending.add(snapshotService.cloneStack(itemStack));
        }
        List<ItemStack> leftovers = placeIntoEmptySlots(targetContents, pending);
        long overflow = denominationService.countStacks(leftovers);
        return new LiveMoneyService.ManagedEnderWalletSyncPlan(
                targetBaseUnits,
                existingTopLevelMoneyValue,
                overflow,
                malformed,
                targetContents
        );
    }

    LiveMoneyService.NormalizationResult normalizeEnderChest(Player player) {
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
        return new LiveMoneyService.NormalizationResult(total, overflow, malformed);
    }

    private List<ItemStack> addToInventory(Inventory inventory, List<ItemStack> itemStacks) {
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack itemStack : itemStacks) {
            leftovers.addAll(inventory.addItem(itemStack).values());
        }
        return leftovers;
    }

    private long maxDeliverableToInventory(
            List<Denomination> denominations,
            long[] existingStackCapacity,
            int remainingSlots,
            long remainingAmount,
            int denominationIndex
    ) {
        if (remainingAmount <= 0L || denominationIndex >= denominations.size()) {
            return 0L;
        }

        Denomination denomination = denominations.get(denominationIndex);
        long denominationValue = denomination.baseUnits();
        int maxStackSize = denomination.material().getMaxStackSize();
        long maxItemsByAmount = remainingAmount / denominationValue;
        if (maxItemsByAmount <= 0L) {
            return 0L;
        }

        long freeExistingItems = existingStackCapacity[denominationIndex];
        if (denominationIndex == denominations.size() - 1) {
            long itemCount = Math.min(maxItemsByAmount, freeExistingItems + ((long) remainingSlots * maxStackSize));
            return itemCount * denominationValue;
        }

        long additionalItemsNeeded = Math.max(0L, maxItemsByAmount - freeExistingItems);
        int maxUsefulSlots = (int) Math.min(
                remainingSlots,
                additionalItemsNeeded == 0L ? 0L : ((additionalItemsNeeded + maxStackSize - 1L) / maxStackSize)
        );

        long best = 0L;
        long previousItemCount = Long.MIN_VALUE;
        for (int slotsUsed = maxUsefulSlots; slotsUsed >= 0; slotsUsed--) {
            long itemCapacity = freeExistingItems + ((long) slotsUsed * maxStackSize);
            long itemCount = Math.min(maxItemsByAmount, itemCapacity);
            if (itemCount == previousItemCount) {
                continue;
            }
            previousItemCount = itemCount;

            long currentValue = itemCount * denominationValue;
            long lowerValue = maxDeliverableToInventory(
                    denominations,
                    existingStackCapacity,
                    remainingSlots - slotsUsed,
                    remainingAmount - currentValue,
                    denominationIndex + 1
            );
            long total = currentValue + lowerValue;
            if (total > best) {
                best = total;
                if (best == remainingAmount) {
                    return best;
                }
            }
        }
        return best;
    }

    private List<ItemStack> addToContents(ItemStack[] contents, List<ItemStack> itemStacks) {
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack itemStack : itemStacks) {
            ItemStack pending = snapshotService.cloneStack(itemStack);
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
                contents[slot] = snapshotService.cloneStack(pending);
                pending.setAmount(0);
            }
            if (pending.getAmount() > 0) {
                leftovers.add(snapshotService.cloneStack(pending));
            }
        }
        return leftovers;
    }

    private List<ItemStack> placeIntoEmptySlots(ItemStack[] contents, List<ItemStack> itemStacks) {
        List<ItemStack> leftovers = new ArrayList<>();
        int nextItemIndex = 0;
        for (int slot = 0; slot < contents.length && nextItemIndex < itemStacks.size(); slot++) {
            if (contents[slot] != null && contents[slot].getType() != Material.AIR) {
                continue;
            }
            contents[slot] = snapshotService.cloneStack(itemStacks.get(nextItemIndex));
            nextItemIndex++;
        }
        for (int index = nextItemIndex; index < itemStacks.size(); index++) {
            leftovers.add(snapshotService.cloneStack(itemStacks.get(index)));
        }
        return leftovers;
    }
}

