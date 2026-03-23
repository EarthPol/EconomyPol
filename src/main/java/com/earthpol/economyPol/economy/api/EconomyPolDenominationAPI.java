package com.earthpol.economyPol.economy.api;

import com.earthpol.economyPol.economy.model.Denomination;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;

public interface EconomyPolDenominationAPI {

    List<Denomination> denominations();

    Optional<Denomination> findDenomination(Material material);

    boolean isMoney(ItemStack itemStack);

    long valueOf(ItemStack itemStack);

    long countValue(Iterable<ItemStack> itemStacks);

    List<ItemStack> materialize(long amount);

    String format(long amount);
}
