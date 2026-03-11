package com.earthpol.economyPol.service;

import com.earthpol.economyPol.config.PluginSettings;
import com.earthpol.economyPol.domain.Denomination;
import com.earthpol.economyPol.domain.MoneyRouteTarget;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LiveMoneyServiceTest {

    private ServerMock server;
    private DenominationService denominationService;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        denominationService = new DenominationService(
                new PluginSettings.CurrencySettings(
                        "Gold Coin",
                        "Gold Coins",
                        List.of(
                                new Denomination(Material.GOLD_NUGGET, 1L),
                                new Denomination(Material.GOLD_INGOT, 9L),
                                new Denomination(Material.GOLD_BLOCK, 81L)
                        )
                ),
                null
        );
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void spendFromLiveSourcesBreaksLargerDenominationIntoChange() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        PlayerMock player = server.addPlayer();
        player.getInventory().addItem(new ItemStack(Material.GOLD_BLOCK, 1));

        LiveMoneyService.SpendResult result = service.spendFromLiveSources(
                player,
                10L,
                List.of(MoneyRouteTarget.INVENTORY, MoneyRouteTarget.ENDER_CHEST, MoneyRouteTarget.CUSTODIAL_ACCOUNT)
        );

        assertTrue(result.success());
        assertEquals(10L, result.requestedAmount());
        assertEquals(81L, result.debitedAmount());
        assertEquals(71L, result.changeAmount());
        assertEquals(71L, service.scanPlayerMoney(player));
        assertFalse(denominationService.isMoney(player.getInventory().getItem(0)) &&
                player.getInventory().getItem(0).getType() == Material.GOLD_BLOCK);
    }

    @Test
    void spendFromLiveSourcesRestoresInventoryWhenChangeCannotFit() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, false)
        );
        PlayerMock player = server.addPlayer();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        player.getInventory().setItem(0, new ItemStack(Material.GOLD_BLOCK, 1));

        LiveMoneyService.SpendResult result = service.spendFromLiveSources(
                player,
                10L,
                List.of(MoneyRouteTarget.INVENTORY, MoneyRouteTarget.CUSTODIAL_ACCOUNT)
        );

        assertFalse(result.success());
        assertEquals("Not enough room to return change.", result.message());
        assertEquals(Material.GOLD_BLOCK, player.getInventory().getItem(0).getType());
        assertEquals(81L, service.scanPlayerMoney(player));
    }
}
