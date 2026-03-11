package com.earthpol.economyPol.service;

import com.earthpol.economyPol.config.PluginSettings;
import com.earthpol.economyPol.domain.Denomination;
import com.earthpol.economyPol.domain.MoneyRouteTarget;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;

public final class LiveMoneyService {

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

    private List<ItemStack> addToInventory(Inventory inventory, List<ItemStack> itemStacks) {
        List<ItemStack> leftovers = new ArrayList<>();
        for (ItemStack itemStack : itemStacks) {
            leftovers.addAll(inventory.addItem(itemStack).values());
        }
        return leftovers;
    }

    public record DeliveryResult(long deliveredToInventory, long deliveredToEnder, long remainder) {}

    public record NormalizationResult(long normalizedValue, long overflow, boolean malformedStacksFound) {}
}
