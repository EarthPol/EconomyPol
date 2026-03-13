package com.earthpol.economyPol.economy.service.support;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.Denomination;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class DenominationService {

    private final PluginSettings.CurrencySettings currencySettings;
    private final List<Denomination> descending;
    private final @Nullable EnhancedLogger logger;

    public DenominationService(PluginSettings.CurrencySettings currencySettings, @Nullable EnhancedLogger logger) {
        this.currencySettings = currencySettings;
        List<Denomination> sorted = new ArrayList<>(currencySettings.denominations());
        sorted.sort(Comparator.comparingLong(Denomination::baseUnits).reversed());
        this.descending = List.copyOf(sorted);
        this.logger = logger;
    }

    public Optional<Denomination> find(Material material) {
        return currencySettings.denominations().stream()
                .filter(denomination -> denomination.material() == material)
                .findFirst();
    }

    public boolean isMoney(ItemStack itemStack) {
        return itemStack != null && itemStack.getType() != Material.AIR && find(itemStack.getType()).isPresent();
    }

    public long valueOf(ItemStack itemStack) {
        return find(itemStack.getType())
                .map(denomination -> denomination.baseUnits() * itemStack.getAmount())
                .orElse(0L);
    }

    public List<Denomination> descending() {
        return descending;
    }

    public List<ItemStack> materialize(long amount) {
        if (amount < 0L) {
            throw new IllegalArgumentException("Cannot materialize a negative amount.");
        }
        long remaining = amount;
        List<ItemStack> stacks = new ArrayList<>();
        for (Denomination denomination : descending) {
            long items = remaining / denomination.baseUnits();
            if (items <= 0L) {
                continue;
            }
            int maxStack = denomination.material().getMaxStackSize();
            while (items > 0L) {
                int stackSize = (int) Math.min(maxStack, items);
                stacks.add(new ItemStack(denomination.material(), stackSize));
                items -= stackSize;
            }
            remaining %= denomination.baseUnits();
        }
        if (remaining != 0L && logger != null) {
            logger.warn("Failed to materialize value cleanly. remainder=" + remaining);
        }
        return stacks;
    }

    public long countStacks(Iterable<ItemStack> itemStacks) {
        long total = 0L;
        for (ItemStack itemStack : itemStacks) {
            if (itemStack == null || itemStack.getType() == Material.AIR) {
                continue;
            }
            total += valueOf(itemStack);
        }
        return total;
    }

    public String format(long amount) {
        return amount + " " + (amount == 1L ? currencySettings.singularName() : currencySettings.pluralName());
    }

    public int fractionalDigits() {
        // Compatibility metadata for Vault APIs. EconomyPol is discrete and integer-only.
        return 0;
    }

    public String singularName() {
        return currencySettings.singularName();
    }

    public String pluralName() {
        return currencySettings.pluralName();
    }
}


