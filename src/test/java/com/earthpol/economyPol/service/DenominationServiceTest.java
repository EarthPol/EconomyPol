package com.earthpol.economyPol.service;

import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.Denomination;
import com.earthpol.economyPol.economy.service.DenominationService;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DenominationServiceTest {

    private final DenominationService denominationService = new DenominationService(
            new PluginSettings.CurrencySettings(
                    "Gold Coin",
                    "Gold Coins",
                    List.of(
                            new Denomination(Material.GOLD_NUGGET, 1),
                            new Denomination(Material.GOLD_INGOT, 9),
                            new Denomination(Material.GOLD_BLOCK, 81)
                    )
            ),
            null
    );

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void materializeUsesHighestDenominationsFirst() {
        List<ItemStack> stacks = denominationService.materialize(100);

        assertEquals(3, stacks.size());
        assertEquals(Material.GOLD_BLOCK, stacks.get(0).getType());
        assertEquals(1, stacks.get(0).getAmount());
        assertEquals(Material.GOLD_INGOT, stacks.get(1).getType());
        assertEquals(2, stacks.get(1).getAmount());
        assertEquals(Material.GOLD_NUGGET, stacks.get(2).getType());
        assertEquals(1, stacks.get(2).getAmount());
    }

    @Test
    void countStacksAndFormatRemainIntegerExact() {
        long value = denominationService.countStacks(List.of(
                new ItemStack(Material.GOLD_BLOCK, 1),
                new ItemStack(Material.GOLD_INGOT, 2),
                new ItemStack(Material.GOLD_NUGGET, 1)
        ));

        assertEquals(100L, value);
        assertEquals("1 Gold Coin", denominationService.format(1));
        assertEquals("2 Gold Coins", denominationService.format(2));
        assertTrue(denominationService.isMoney(new ItemStack(Material.GOLD_BLOCK, 1)));
    }
}
