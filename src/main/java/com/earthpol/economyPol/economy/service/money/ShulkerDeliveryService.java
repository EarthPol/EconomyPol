package com.earthpol.economyPol.economy.service.money;

import com.earthpol.economyPol.economy.model.Denomination;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ShulkerDeliveryService {

    record DeliveryResult(long delivered, long remainder) {}

    record StripResult(long removedValue, boolean malformedStacksFound) {}

    record NestedMoneyReference(int outerSlot, int innerSlot, Denomination denomination) {}

    private final DenominationService denominationService;

    ShulkerDeliveryService(DenominationService denominationService) {
        this.denominationService = denominationService;
    }

    long countMoneyInTopLevelGoldShulkers(ItemStack[] topLevelContents) {
        long total = 0L;
        for (ItemStack itemStack : topLevelContents) {
            total += countMoneyInGoldShulker(itemStack);
        }
        return total;
    }

    long countMoneyInGoldShulker(ItemStack itemStack) {
        return readGoldShulkerContents(itemStack)
                .map(this::countSimpleContents)
                .orElse(0L);
    }

    StripResult stripMoneyFromTopLevelGoldShulkers(ItemStack[] topLevelContents) {
        long removedValue = 0L;
        boolean malformedStacksFound = false;

        for (int outerSlot = 0; outerSlot < topLevelContents.length; outerSlot++) {
            ItemStack itemStack = topLevelContents[outerSlot];
            Optional<ItemStack[]> shulkerContents = readGoldShulkerContents(itemStack);
            if (shulkerContents.isEmpty()) {
                continue;
            }

            ItemStack[] innerContents = shulkerContents.get();
            boolean changed = false;
            for (int innerSlot = 0; innerSlot < innerContents.length; innerSlot++) {
                ItemStack inner = innerContents[innerSlot];
                if (!denominationService.isMoney(inner)) {
                    continue;
                }
                if (inner.getAmount() > inner.getMaxStackSize()) {
                    malformedStacksFound = true;
                }
                removedValue += denominationService.valueOf(inner);
                innerContents[innerSlot] = null;
                changed = true;
            }
            if (changed) {
                topLevelContents[outerSlot] = writeGoldShulkerContents(itemStack, innerContents).orElse(itemStack);
            }
        }

        return new StripResult(removedValue, malformedStacksFound);
    }

    long removeFromTopLevelGoldShulkers(Inventory inventory, long amount) {
        long remaining = amount;
        for (int outerSlot = 0; outerSlot < inventory.getSize() && remaining > 0L; outerSlot++) {
            ItemStack itemStack = inventory.getItem(outerSlot);
            Optional<ItemStack[]> shulkerContents = readGoldShulkerContents(itemStack);
            if (shulkerContents.isEmpty()) {
                continue;
            }

            ItemStack[] innerContents = shulkerContents.get();
            long before = remaining;
            remaining = removeFromContents(innerContents, remaining);
            if (remaining != before) {
                inventory.setItem(outerSlot, writeGoldShulkerContents(itemStack, innerContents).orElse(itemStack));
            }
        }
        return amount - remaining;
    }

    long removeFromTopLevelGoldShulkers(ItemStack[] topLevelContents, long amount) {
        long remaining = amount;
        for (int outerSlot = 0; outerSlot < topLevelContents.length && remaining > 0L; outerSlot++) {
            ItemStack itemStack = topLevelContents[outerSlot];
            Optional<ItemStack[]> shulkerContents = readGoldShulkerContents(itemStack);
            if (shulkerContents.isEmpty()) {
                continue;
            }

            ItemStack[] innerContents = shulkerContents.get();
            long before = remaining;
            remaining = removeFromContents(innerContents, remaining);
            if (remaining != before) {
                topLevelContents[outerSlot] = writeGoldShulkerContents(itemStack, innerContents).orElse(itemStack);
            }
        }
        return amount - remaining;
    }

    NestedMoneyReference findSmallestOverpayCandidateInTopLevelGoldShulkers(
            Inventory inventory,
            long remaining,
            NestedMoneyReference currentBest
    ) {
        NestedMoneyReference best = currentBest;
        for (int outerSlot = 0; outerSlot < inventory.getSize(); outerSlot++) {
            ItemStack itemStack = inventory.getItem(outerSlot);
            Optional<ItemStack[]> shulkerContents = readGoldShulkerContents(itemStack);
            if (shulkerContents.isEmpty()) {
                continue;
            }
            best = findSmallestOverpayCandidateInContents(shulkerContents.get(), outerSlot, remaining, best);
        }
        return best;
    }

    NestedMoneyReference findSmallestOverpayCandidateInTopLevelGoldShulkers(
            ItemStack[] topLevelContents,
            long remaining,
            NestedMoneyReference currentBest
    ) {
        NestedMoneyReference best = currentBest;
        for (int outerSlot = 0; outerSlot < topLevelContents.length; outerSlot++) {
            Optional<ItemStack[]> shulkerContents = readGoldShulkerContents(topLevelContents[outerSlot]);
            if (shulkerContents.isEmpty()) {
                continue;
            }
            best = findSmallestOverpayCandidateInContents(shulkerContents.get(), outerSlot, remaining, best);
        }
        return best;
    }

    void decrementNestedItem(Inventory inventory, int outerSlot, int innerSlot) {
        ItemStack itemStack = inventory.getItem(outerSlot);
        ItemStack[] innerContents = readGoldShulkerContents(itemStack)
                .orElseThrow(() -> new IllegalStateException("Expected gold shulker in slot " + outerSlot + "."));
        decrementNestedItem(innerContents, innerSlot);
        inventory.setItem(outerSlot, writeGoldShulkerContents(itemStack, innerContents).orElse(itemStack));
    }

    void decrementNestedItem(ItemStack[] topLevelContents, int outerSlot, int innerSlot) {
        ItemStack itemStack = topLevelContents[outerSlot];
        ItemStack[] innerContents = readGoldShulkerContents(itemStack)
                .orElseThrow(() -> new IllegalStateException("Expected gold shulker in slot " + outerSlot + "."));
        decrementNestedItem(innerContents, innerSlot);
        topLevelContents[outerSlot] = writeGoldShulkerContents(itemStack, innerContents).orElse(itemStack);
    }

    DeliveryResult deliverToTopLevelGoldShulkers(Inventory inventory, long amount) {
        long deliverable = maxDeliverableToTopLevelGoldShulkers(inventory.getContents(), amount);
        if (deliverable <= 0L) {
            return new DeliveryResult(0L, amount);
        }

        List<ItemStack> leftovers = addToTopLevelGoldShulkers(inventory, denominationService.materialize(deliverable));
        long delivered = deliverable - denominationService.countStacks(leftovers);
        return new DeliveryResult(delivered, Math.max(0L, amount - delivered));
    }

    DeliveryResult deliverToTopLevelGoldShulkers(ItemStack[] topLevelContents, long amount) {
        long deliverable = maxDeliverableToTopLevelGoldShulkers(topLevelContents, amount);
        if (deliverable <= 0L) {
            return new DeliveryResult(0L, amount);
        }

        List<ItemStack> leftovers = addToTopLevelGoldShulkers(topLevelContents, denominationService.materialize(deliverable));
        long delivered = deliverable - denominationService.countStacks(leftovers);
        return new DeliveryResult(delivered, Math.max(0L, amount - delivered));
    }

    long maxDeliverableToTopLevelGoldShulkers(ItemStack[] topLevelContents, long maxAmount) {
        if (maxAmount <= 0L) {
            return 0L;
        }

        List<Denomination> denominations = denominationService.descending();
        long[] existingStackCapacity = new long[denominations.size()];
        int emptySlots = 0;

        for (ItemStack itemStack : topLevelContents) {
            Optional<ItemStack[]> shulkerContents = readGoldShulkerContents(itemStack);
            if (shulkerContents.isEmpty()) {
                continue;
            }
            CapacitySnapshot snapshot = capacitySnapshot(shulkerContents.get(), denominations);
            for (int index = 0; index < existingStackCapacity.length; index++) {
                existingStackCapacity[index] += snapshot.existingStackCapacity()[index];
            }
            emptySlots += snapshot.emptySlots();
        }

        return maxDeliverableToContents(
                denominations,
                existingStackCapacity,
                emptySlots,
                Math.max(0L, maxAmount),
                0
        );
    }

    private NestedMoneyReference findSmallestOverpayCandidateInContents(
            ItemStack[] innerContents,
            int outerSlot,
            long remaining,
            NestedMoneyReference currentBest
    ) {
        NestedMoneyReference best = currentBest;
        for (int innerSlot = 0; innerSlot < innerContents.length; innerSlot++) {
            ItemStack itemStack = innerContents[innerSlot];
            if (!denominationService.isMoney(itemStack)) {
                continue;
            }
            Denomination denomination = denominationService.find(itemStack.getType()).orElseThrow();
            if (denomination.baseUnits() <= remaining) {
                continue;
            }
            if (best == null || denomination.baseUnits() < best.denomination().baseUnits()) {
                best = new NestedMoneyReference(outerSlot, innerSlot, denomination);
            }
        }
        return best;
    }

    private long removeFromContents(ItemStack[] contents, long amount) {
        long remaining = amount;
        for (Denomination denomination : denominationService.descending()) {
            remaining = removeMaterial(contents, denomination, remaining);
            if (remaining <= 0L) {
                return 0L;
            }
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

    private void decrementNestedItem(ItemStack[] contents, int innerSlot) {
        ItemStack itemStack = contents[innerSlot];
        if (itemStack == null || itemStack.getType().isAir()) {
            throw new IllegalStateException("Expected money item in shulker slot " + innerSlot + ".");
        }
        itemStack.setAmount(itemStack.getAmount() - 1);
        if (itemStack.getAmount() <= 0) {
            contents[innerSlot] = null;
        } else {
            contents[innerSlot] = itemStack;
        }
    }

    private List<ItemStack> addToTopLevelGoldShulkers(Inventory inventory, List<ItemStack> itemStacks) {
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack itemStack : itemStacks) {
            ItemStack pending = cloneStack(itemStack);
            if (pending == null || pending.getType().isAir()) {
                continue;
            }
            pending = addToShulkerStacks(inventory, pending, true);
            pending = addToShulkerStacks(inventory, pending, false);
            if (pending != null && pending.getAmount() > 0) {
                leftovers.add(cloneStack(pending));
            }
        }
        return leftovers;
    }

    private List<ItemStack> addToTopLevelGoldShulkers(ItemStack[] topLevelContents, List<ItemStack> itemStacks) {
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack itemStack : itemStacks) {
            ItemStack pending = cloneStack(itemStack);
            if (pending == null || pending.getType().isAir()) {
                continue;
            }
            pending = addToShulkerStacks(topLevelContents, pending, true);
            pending = addToShulkerStacks(topLevelContents, pending, false);
            if (pending != null && pending.getAmount() > 0) {
                leftovers.add(cloneStack(pending));
            }
        }
        return leftovers;
    }

    private ItemStack addToShulkerStacks(Inventory inventory, ItemStack pending, boolean existingOnly) {
        ItemStack remaining = pending;
        for (int outerSlot = 0; outerSlot < inventory.getSize() && remaining != null && remaining.getAmount() > 0; outerSlot++) {
            ItemStack itemStack = inventory.getItem(outerSlot);
            Optional<ItemStack[]> shulkerContents = readGoldShulkerContents(itemStack);
            if (shulkerContents.isEmpty()) {
                continue;
            }

            ItemStack[] innerContents = shulkerContents.get();
            remaining = existingOnly
                    ? addToExistingStacks(innerContents, remaining)
                    : addToEmptySlots(innerContents, remaining);
            inventory.setItem(outerSlot, writeGoldShulkerContents(itemStack, innerContents).orElse(itemStack));
        }
        return remaining;
    }

    private ItemStack addToShulkerStacks(ItemStack[] topLevelContents, ItemStack pending, boolean existingOnly) {
        ItemStack remaining = pending;
        for (int outerSlot = 0; outerSlot < topLevelContents.length && remaining != null && remaining.getAmount() > 0; outerSlot++) {
            ItemStack itemStack = topLevelContents[outerSlot];
            Optional<ItemStack[]> shulkerContents = readGoldShulkerContents(itemStack);
            if (shulkerContents.isEmpty()) {
                continue;
            }

            ItemStack[] innerContents = shulkerContents.get();
            remaining = existingOnly
                    ? addToExistingStacks(innerContents, remaining)
                    : addToEmptySlots(innerContents, remaining);
            topLevelContents[outerSlot] = writeGoldShulkerContents(itemStack, innerContents).orElse(itemStack);
        }
        return remaining;
    }

    private ItemStack addToExistingStacks(ItemStack[] contents, ItemStack pending) {
        ItemStack remaining = cloneStack(pending);
        for (int slot = 0; slot < contents.length && remaining.getAmount() > 0; slot++) {
            ItemStack existing = contents[slot];
            if (existing == null || existing.getType() == Material.AIR || !existing.isSimilar(remaining)) {
                continue;
            }
            int space = existing.getMaxStackSize() - existing.getAmount();
            if (space <= 0) {
                continue;
            }
            int moved = Math.min(space, remaining.getAmount());
            existing.setAmount(existing.getAmount() + moved);
            remaining.setAmount(remaining.getAmount() - moved);
            contents[slot] = existing;
        }
        return remaining.getAmount() > 0 ? remaining : null;
    }

    private ItemStack addToEmptySlots(ItemStack[] contents, ItemStack pending) {
        ItemStack remaining = cloneStack(pending);
        for (int slot = 0; slot < contents.length && remaining.getAmount() > 0; slot++) {
            ItemStack existing = contents[slot];
            if (existing != null && existing.getType() != Material.AIR) {
                continue;
            }
            int placed = Math.min(remaining.getAmount(), remaining.getMaxStackSize());
            ItemStack placedStack = cloneStack(remaining);
            placedStack.setAmount(placed);
            contents[slot] = placedStack;
            remaining.setAmount(remaining.getAmount() - placed);
        }
        return remaining.getAmount() > 0 ? remaining : null;
    }

    private CapacitySnapshot capacitySnapshot(ItemStack[] contents, List<Denomination> denominations) {
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
        return new CapacitySnapshot(existingStackCapacity, emptySlots);
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

    private Optional<ItemStack[]> readGoldShulkerContents(ItemStack itemStack) {
        if (!isGoldShulker(itemStack)) {
            return Optional.empty();
        }
        if (!(itemStack.getItemMeta() instanceof BlockStateMeta blockStateMeta)) {
            return Optional.empty();
        }
        if (!(blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox)) {
            return Optional.empty();
        }
        return Optional.of(cloneContents(shulkerBox.getInventory().getContents()));
    }

    private Optional<ItemStack> writeGoldShulkerContents(ItemStack itemStack, ItemStack[] contents) {
        if (!isGoldShulker(itemStack)) {
            return Optional.empty();
        }
        ItemStack updated = cloneStack(itemStack);
        if (!(updated.getItemMeta() instanceof BlockStateMeta blockStateMeta)) {
            return Optional.empty();
        }
        if (!(blockStateMeta.getBlockState() instanceof ShulkerBox shulkerBox)) {
            return Optional.empty();
        }
        shulkerBox.getInventory().setContents(cloneContents(contents));
        blockStateMeta.setBlockState(shulkerBox);
        updated.setItemMeta(blockStateMeta);
        return Optional.of(updated);
    }

    private long countSimpleContents(ItemStack[] contents) {
        long total = 0L;
        for (ItemStack itemStack : contents) {
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                continue;
            }
            total += denominationService.valueOf(itemStack);
        }
        return total;
    }

    private boolean isGoldShulker(ItemStack itemStack) {
        return itemStack != null && itemStack.getType() == Material.YELLOW_SHULKER_BOX;
    }

    private ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] clone = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            clone[index] = cloneStack(contents[index]);
        }
        return clone;
    }

    private ItemStack cloneStack(ItemStack itemStack) {
        return itemStack == null ? null : itemStack.clone();
    }

    private record CapacitySnapshot(long[] existingStackCapacity, int emptySlots) {}
}
