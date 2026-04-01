package com.earthpol.economyPol.economy.service.money;

import com.earthpol.economyPol.economy.model.Denomination;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;

final class LiveMoneyDeliveryService {

    private record PlacementResult(long delivered, long remainder) {}

    private final DenominationService denominationService;
    private final LiveMoneySnapshotService snapshotService;
    private final ShulkerDeliveryService shulkerDeliveryService;

    LiveMoneyDeliveryService(
            DenominationService denominationService,
            LiveMoneySnapshotService snapshotService,
            ShulkerDeliveryService shulkerDeliveryService
    ) {
        this.denominationService = denominationService;
        this.snapshotService = snapshotService;
        this.shulkerDeliveryService = shulkerDeliveryService;
    }

    LiveMoneyService.DeliveryResult deliver(Player player, long amount, List<MoneyRouteTarget> routingOrder) {
        return deliver(player, amount, routingOrder, true);
    }

    LiveMoneyService.DeliveryResult deliver(
            Player player,
            long amount,
            List<MoneyRouteTarget> routingOrder,
            boolean allowShulkerDelivery
    ) {
        long deliveredToInventory = 0L;
        long deliveredToEnder = 0L;

        for (MoneyRouteTarget target : routingOrder) {
            if (amount <= 0L || target == MoneyRouteTarget.CUSTODIAL_ACCOUNT) {
                break;
            }
            PlacementResult placement = switch (target) {
                case INVENTORY -> deliverToInventory(player, amount, allowShulkerDelivery);
                case ENDER_CHEST -> deliverToInventory(player.getEnderChest(), amount, allowShulkerDelivery);
                case CUSTODIAL_ACCOUNT -> new PlacementResult(0L, amount);
            };
            long deliveredNow = placement.delivered();
            if (target == MoneyRouteTarget.INVENTORY) {
                deliveredToInventory += deliveredNow;
            } else {
                deliveredToEnder += deliveredNow;
            }
            amount = placement.remainder();
        }

        return new LiveMoneyService.DeliveryResult(deliveredToInventory, deliveredToEnder, amount);
    }

    long maxDeliverableToInventory(LiveMoneyService.LiveContainerSnapshot snapshot, long maxAmount) {
        long deliveredToShulkers = shulkerDeliveryService.maxDeliverableToTopLevelGoldShulkers(snapshot.inventoryContents(), maxAmount);
        deliveredToShulkers += shulkerDeliveryService.maxDeliverableToTopLevelGoldShulkers(
                new ItemStack[] {snapshot.offHand()},
                Math.max(0L, maxAmount - deliveredToShulkers)
        );
        long remaining = Math.max(0L, maxAmount - deliveredToShulkers);
        return deliveredToShulkers + maxDeliverableToContents(snapshot.inventoryContents(), remaining);
    }

    LiveMoneyService.DeliveryResult deliver(LiveMoneySimulatedState simulatedState, long amount, List<MoneyRouteTarget> routingOrder) {
        return deliver(simulatedState, amount, routingOrder, true);
    }

    LiveMoneyService.DeliveryResult deliver(
            LiveMoneySimulatedState simulatedState,
            long amount,
            List<MoneyRouteTarget> routingOrder,
            boolean allowShulkerDelivery
    ) {
        long remaining = amount;
        long deliveredToInventory = 0L;
        long deliveredToEnder = 0L;

        for (MoneyRouteTarget target : routingOrder) {
            if (remaining <= 0L || target == MoneyRouteTarget.CUSTODIAL_ACCOUNT) {
                break;
            }
            PlacementResult placement = target == MoneyRouteTarget.INVENTORY
                    ? deliverToInventory(simulatedState, remaining, allowShulkerDelivery)
                    : deliverToContents(simulatedState.enderChestContents(), remaining, allowShulkerDelivery);
            long deliveredNow = placement.delivered();
            if (target == MoneyRouteTarget.INVENTORY) {
                deliveredToInventory += deliveredNow;
            } else {
                deliveredToEnder += deliveredNow;
            }
            remaining = placement.remainder();
        }

        return new LiveMoneyService.DeliveryResult(deliveredToInventory, deliveredToEnder, remaining);
    }

    LiveMoneyService.ManagedEnderWalletSyncPlan planManagedEnderWalletSync(ItemStack[] currentContents, long targetBaseUnits) {
        ItemStack[] targetContents = snapshotService.cloneContents(currentContents);
        long existingManagedMoneyValue = 0L;
        boolean malformed = false;

        for (int slot = 0; slot < targetContents.length; slot++) {
            ItemStack itemStack = targetContents[slot];
            if (!denominationService.isMoney(itemStack)) {
                continue;
            }
            if (itemStack.getAmount() > itemStack.getMaxStackSize()) {
                malformed = true;
            }
            existingManagedMoneyValue += denominationService.valueOf(itemStack);
            targetContents[slot] = null;
        }

        ShulkerDeliveryService.StripResult stripResult =
                shulkerDeliveryService.stripMoneyFromTopLevelGoldShulkers(targetContents);
        existingManagedMoneyValue += stripResult.removedValue();
        malformed |= stripResult.malformedStacksFound();

        ShulkerDeliveryService.DeliveryResult shulkerPlacement =
                shulkerDeliveryService.deliverToTopLevelGoldShulkers(targetContents, targetBaseUnits);
        long remaining = shulkerPlacement.remainder();
        long topLevelDeliverable = maxDeliverableToContents(targetContents, remaining);
        List<ItemStack> leftovers = placeIntoEmptySlots(targetContents, denominationService.materialize(topLevelDeliverable));
        long deliveredTopLevel = topLevelDeliverable - denominationService.countStacks(leftovers);
        long delivered = shulkerPlacement.delivered() + deliveredTopLevel;
        long overflow = Math.max(0L, targetBaseUnits - delivered);
        return new LiveMoneyService.ManagedEnderWalletSyncPlan(
                targetBaseUnits,
                existingManagedMoneyValue,
                overflow,
                malformed,
                targetContents
        );
    }

    LiveMoneyService.NormalizationResult normalizeEnderChest(Player player) {
        Inventory enderChest = player.getEnderChest();
        long total = 0L;
        boolean malformed = false;
        ItemStack[] normalizedContents = snapshotService.cloneContents(enderChest.getContents());
        for (int slot = 0; slot < normalizedContents.length; slot++) {
            ItemStack itemStack = normalizedContents[slot];
            if (!denominationService.isMoney(itemStack)) {
                continue;
            }
            if (itemStack.getAmount() > itemStack.getMaxStackSize()) {
                malformed = true;
            }
            total += denominationService.valueOf(itemStack);
            normalizedContents[slot] = null;
        }

        ShulkerDeliveryService.StripResult stripResult =
                shulkerDeliveryService.stripMoneyFromTopLevelGoldShulkers(normalizedContents);
        total += stripResult.removedValue();
        malformed |= stripResult.malformedStacksFound();

        ShulkerDeliveryService.DeliveryResult shulkerPlacement =
                shulkerDeliveryService.deliverToTopLevelGoldShulkers(normalizedContents, total);
        long remaining = shulkerPlacement.remainder();
        long topLevelDeliverable = maxDeliverableToContents(normalizedContents, remaining);
        List<ItemStack> leftovers = addToContents(normalizedContents, denominationService.materialize(topLevelDeliverable));
        long deliveredTopLevel = topLevelDeliverable - denominationService.countStacks(leftovers);
        long delivered = shulkerPlacement.delivered() + deliveredTopLevel;
        long overflow = Math.max(0L, total - delivered);
        enderChest.setContents(snapshotService.cloneContents(normalizedContents));
        return new LiveMoneyService.NormalizationResult(total, overflow, malformed);
    }

    private PlacementResult deliverToInventory(Inventory inventory, long remainingAmount, boolean allowShulkerDelivery) {
        long deliveredToShulkers = 0L;
        long remaining = remainingAmount;
        if (allowShulkerDelivery) {
            ShulkerDeliveryService.DeliveryResult shulkerPlacement =
                    shulkerDeliveryService.deliverToTopLevelGoldShulkers(inventory, remaining);
            deliveredToShulkers = shulkerPlacement.delivered();
            remaining = shulkerPlacement.remainder();
        }
        long deliverable = maxDeliverableToContents(inventory.getContents(), remaining);
        if (deliverable <= 0L) {
            return new PlacementResult(deliveredToShulkers, remaining);
        }
        List<ItemStack> leftovers = addToInventory(inventory, denominationService.materialize(deliverable));
        long delivered = deliverable - denominationService.countStacks(leftovers);
        return new PlacementResult(
                deliveredToShulkers + delivered,
                Math.max(0L, remaining - delivered)
        );
    }

    private PlacementResult deliverToInventory(Player player, long remainingAmount, boolean allowShulkerDelivery) {
        PlayerInventory inventory = player.getInventory();
        long deliveredToShulkers = 0L;
        long remaining = remainingAmount;
        ItemStack[] storageContents = inventory.getStorageContents();

        if (allowShulkerDelivery) {
            ShulkerDeliveryService.DeliveryResult inventoryShulkerPlacement =
                    shulkerDeliveryService.deliverToTopLevelGoldShulkers(storageContents, remaining);
            deliveredToShulkers += inventoryShulkerPlacement.delivered();
            remaining = inventoryShulkerPlacement.remainder();

            ItemStack[] offHandContents = new ItemStack[] {inventory.getItemInOffHand()};
            ShulkerDeliveryService.DeliveryResult offHandPlacement =
                    shulkerDeliveryService.deliverToTopLevelGoldShulkers(offHandContents, remaining);
            inventory.setItemInOffHand(offHandContents[0]);
            deliveredToShulkers += offHandPlacement.delivered();
            remaining = offHandPlacement.remainder();
        }

        long deliverable = maxDeliverableToContents(storageContents, remaining);
        if (deliverable <= 0L) {
            inventory.setStorageContents(storageContents);
            return new PlacementResult(deliveredToShulkers, remaining);
        }
        List<ItemStack> leftovers = addToContents(storageContents, denominationService.materialize(deliverable));
        inventory.setStorageContents(storageContents);
        long delivered = deliverable - denominationService.countStacks(leftovers);
        return new PlacementResult(
                deliveredToShulkers + delivered,
                Math.max(0L, remaining - delivered)
        );
    }

    private PlacementResult deliverToInventory(
            LiveMoneySimulatedState simulatedState,
            long remainingAmount,
            boolean allowShulkerDelivery
    ) {
        long deliveredToShulkers = 0L;
        long remaining = remainingAmount;

        if (allowShulkerDelivery) {
            ShulkerDeliveryService.DeliveryResult inventoryShulkerPlacement =
                    shulkerDeliveryService.deliverToTopLevelGoldShulkers(simulatedState.inventoryContents(), remaining);
            deliveredToShulkers += inventoryShulkerPlacement.delivered();
            remaining = inventoryShulkerPlacement.remainder();

            ItemStack[] offHandContents = new ItemStack[] {simulatedState.offHand()};
            ShulkerDeliveryService.DeliveryResult offHandPlacement =
                    shulkerDeliveryService.deliverToTopLevelGoldShulkers(offHandContents, remaining);
            simulatedState.setOffHand(offHandContents[0]);
            deliveredToShulkers += offHandPlacement.delivered();
            remaining = offHandPlacement.remainder();
        }

        long deliverable = maxDeliverableToContents(simulatedState.inventoryContents(), remaining);
        if (deliverable <= 0L) {
            return new PlacementResult(deliveredToShulkers, remaining);
        }
        List<ItemStack> leftovers = addToContents(simulatedState.inventoryContents(), denominationService.materialize(deliverable));
        long delivered = deliverable - denominationService.countStacks(leftovers);
        return new PlacementResult(
                deliveredToShulkers + delivered,
                Math.max(0L, remaining - delivered)
        );
    }

    private PlacementResult deliverToContents(ItemStack[] contents, long remainingAmount, boolean allowShulkerDelivery) {
        long deliveredToShulkers = 0L;
        long remaining = remainingAmount;
        if (allowShulkerDelivery) {
            ShulkerDeliveryService.DeliveryResult shulkerPlacement =
                    shulkerDeliveryService.deliverToTopLevelGoldShulkers(contents, remaining);
            deliveredToShulkers = shulkerPlacement.delivered();
            remaining = shulkerPlacement.remainder();
        }
        long deliverable = maxDeliverableToContents(contents, remaining);
        if (deliverable <= 0L) {
            return new PlacementResult(deliveredToShulkers, remaining);
        }
        List<ItemStack> leftovers = addToContents(contents, denominationService.materialize(deliverable));
        long delivered = deliverable - denominationService.countStacks(leftovers);
        return new PlacementResult(
                deliveredToShulkers + delivered,
                Math.max(0L, remaining - delivered)
        );
    }

    private long maxDeliverableToContents(ItemStack[] contents, long maxAmount) {
        if (maxAmount <= 0L) {
            return 0L;
        }

        List<Denomination> denominations = denominationService.descending();
        long[] existingStackCapacity = new long[denominations.size()];
        int emptySlots = 0;
        for (ItemStack itemStack : contents) {
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
        return maxDeliverableToContents(
                denominations,
                existingStackCapacity,
                emptySlots,
                Math.max(0L, maxAmount),
                0
        );
    }

    private List<ItemStack> addToInventory(Inventory inventory, List<ItemStack> itemStacks) {
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack itemStack : itemStacks) {
            leftovers.addAll(inventory.addItem(itemStack).values());
        }
        return leftovers;
    }

    private long maxDeliverableToContents(
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
            return maxDeliverableToContents(
                    denominations,
                    existingStackCapacity,
                    remainingSlots,
                    remainingAmount,
                    denominationIndex + 1
            );
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
            long lowerValue = maxDeliverableToContents(
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
