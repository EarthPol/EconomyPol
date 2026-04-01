package com.earthpol.economyPol.service;

import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.Denomination;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import com.earthpol.economyPol.economy.service.money.LiveMoneyService;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.Arrays;
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
                List.of(MoneyRouteTarget.INVENTORY, MoneyRouteTarget.ENDER_CHEST, MoneyRouteTarget.CUSTODIAL_ACCOUNT),
                PluginSettings.ChangeOverflowPolicy.FAIL
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
                List.of(MoneyRouteTarget.INVENTORY, MoneyRouteTarget.CUSTODIAL_ACCOUNT),
                PluginSettings.ChangeOverflowPolicy.FAIL
        );

        assertFalse(result.success());
        assertEquals("Not enough room to return change.", result.message());
        assertEquals(Material.GOLD_BLOCK, player.getInventory().getItem(0).getType());
        assertEquals(81L, service.scanPlayerMoney(player));
    }

    @Test
    void spendFromLiveSourcesRoutesUnplaceableChangeToCustodialWhenConfigured() {
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
                List.of(MoneyRouteTarget.INVENTORY, MoneyRouteTarget.CUSTODIAL_ACCOUNT),
                PluginSettings.ChangeOverflowPolicy.CUSTODIAL
        );

        assertTrue(result.success());
        assertEquals(71L, result.changeAmount());
        assertEquals(8L, result.changeRoutedToCustodial());
        assertEquals(63L, service.scanPlayerMoney(player));
        assertEquals(Material.GOLD_INGOT, player.getInventory().getItem(0).getType());
    }

    @Test
    void planManagedEnderWalletSyncReplacesExistingTopLevelMoneyWithSnapshotValue() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        ItemStack[] currentContents = new ItemStack[27];
        currentContents[0] = new ItemStack(Material.GOLD_BLOCK, 1);
        currentContents[5] = new ItemStack(Material.DIAMOND, 3);

        LiveMoneyService.ManagedEnderWalletSyncPlan plan = service.planManagedEnderWalletSync(currentContents, 10L);

        assertEquals(10L, plan.targetBaseUnits());
        assertEquals(81L, plan.existingManagedMoneyValue());
        assertEquals(0L, plan.overflow());
        assertFalse(plan.malformedStacksFound());
        assertEquals(Material.DIAMOND, plan.targetContents()[5].getType());
        assertEquals(10L, denominationService.countStacks(Arrays.asList(plan.targetContents())));
    }

    @Test
    void planManagedEnderWalletSyncReportsOverflowWhenNoSlotsAreAvailable() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        ItemStack[] currentContents = new ItemStack[2];
        currentContents[0] = new ItemStack(Material.STONE, 64);
        currentContents[1] = new ItemStack(Material.DIAMOND, 1);

        LiveMoneyService.ManagedEnderWalletSyncPlan plan = service.planManagedEnderWalletSync(currentContents, 10L);

        assertEquals(0L, plan.existingManagedMoneyValue());
        assertEquals(10L, plan.overflow());
        assertEquals(Material.STONE, plan.targetContents()[0].getType());
        assertEquals(Material.DIAMOND, plan.targetContents()[1].getType());
    }

    @Test
    void scanPlayerMoneyCountsInventoryGoldShulkerContents() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, goldShulkerWithContents(
                new ItemStack(Material.GOLD_INGOT, 2),
                new ItemStack(Material.GOLD_NUGGET, 3)
        ));

        assertEquals(21L, service.scanPlayerMoney(player));
    }

    @Test
    void scanPlayerMoneyCountsOffHandMoneyExactlyOnce() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInOffHand(new ItemStack(Material.GOLD_INGOT, 1));

        assertEquals(9L, service.scanPlayerMoney(player));
    }

    @Test
    void scanPlayerMoneyCountsOffHandGoldShulkerContents() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInOffHand(goldShulkerWithContents(new ItemStack(Material.GOLD_NUGGET, 7)));

        assertEquals(7L, service.scanPlayerMoney(player));
    }

    @Test
    void spendFromLiveSourcesUsesGoldShulkerBeforeTopLevelInventory() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, goldShulkerWithContents(new ItemStack(Material.GOLD_NUGGET, 10)));
        player.getInventory().setItem(1, new ItemStack(Material.GOLD_INGOT, 1));

        LiveMoneyService.SpendResult result = service.spendFromLiveSources(
                player,
                5L,
                List.of(MoneyRouteTarget.INVENTORY, MoneyRouteTarget.ENDER_CHEST, MoneyRouteTarget.CUSTODIAL_ACCOUNT),
                PluginSettings.ChangeOverflowPolicy.FAIL
        );

        assertTrue(result.success());
        assertEquals(14L, service.scanPlayerMoney(player));
        assertEquals(5L, countGoldShulkerMoney(player.getInventory().getItem(0)));
        assertEquals(Material.GOLD_INGOT, player.getInventory().getItem(1).getType());
        assertEquals(9L, denominationService.valueOf(player.getInventory().getItem(1)));
    }

    @Test
    void spendFromLiveSourcesUsesOffHandGoldShulkerBeforeTopLevelInventory() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInOffHand(goldShulkerWithContents(new ItemStack(Material.GOLD_NUGGET, 10)));
        player.getInventory().setItem(0, new ItemStack(Material.GOLD_INGOT, 1));

        LiveMoneyService.SpendResult result = service.spendFromLiveSources(
                player,
                5L,
                List.of(MoneyRouteTarget.INVENTORY, MoneyRouteTarget.ENDER_CHEST, MoneyRouteTarget.CUSTODIAL_ACCOUNT),
                PluginSettings.ChangeOverflowPolicy.FAIL
        );

        assertTrue(result.success());
        assertEquals(14L, service.scanPlayerMoney(player));
        assertEquals(5L, countGoldShulkerMoney(player.getInventory().getItemInOffHand()));
        assertEquals(Material.GOLD_INGOT, player.getInventory().getItem(0).getType());
        assertEquals(9L, denominationService.valueOf(player.getInventory().getItem(0)));
    }

    @Test
    void deliverPlacesMoneyIntoGoldShulkersWhenEnabled() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, goldShulkerWithContents());

        LiveMoneyService.DeliveryResult result = service.deliver(
                player,
                10L,
                List.of(MoneyRouteTarget.INVENTORY),
                true
        );

        assertEquals(10L, result.deliveredToInventory());
        assertEquals(0L, result.remainder());
        assertEquals(10L, countGoldShulkerMoney(player.getInventory().getItem(0)));
        assertEquals(0L, countTopLevelMoney(player.getInventory().getContents()));
    }

    @Test
    void deliverSkipsGoldShulkersWhenDisabled() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, goldShulkerWithContents());

        LiveMoneyService.DeliveryResult result = service.deliver(
                player,
                10L,
                List.of(MoneyRouteTarget.INVENTORY),
                false
        );

        assertEquals(10L, result.deliveredToInventory());
        assertEquals(0L, result.remainder());
        assertEquals(0L, countGoldShulkerMoney(player.getInventory().getItem(0)));
        assertEquals(10L, countTopLevelMoney(player.getInventory().getContents()));
    }

    @Test
    void deliverPlacesMoneyIntoOffHandGoldShulkerWhenEnabled() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        PlayerMock player = server.addPlayer();
        player.getInventory().setItemInOffHand(goldShulkerWithContents());

        LiveMoneyService.DeliveryResult result = service.deliver(
                player,
                10L,
                List.of(MoneyRouteTarget.INVENTORY),
                true
        );

        assertEquals(10L, result.deliveredToInventory());
        assertEquals(0L, result.remainder());
        assertEquals(10L, countGoldShulkerMoney(player.getInventory().getItemInOffHand()));
        assertEquals(0L, countTopLevelMoney(player.getInventory().getStorageContents()));
    }

    @Test
    void planManagedEnderWalletSyncCountsGoldShulkerMoney() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        ItemStack[] currentContents = new ItemStack[27];
        currentContents[0] = goldShulkerWithContents(new ItemStack(Material.GOLD_BLOCK, 1));

        LiveMoneyService.ManagedEnderWalletSyncPlan plan = service.planManagedEnderWalletSync(currentContents, 10L);

        assertEquals(81L, plan.existingManagedMoneyValue());
        PlayerMock player = server.addPlayer();
        player.getEnderChest().setContents(plan.targetContents());
        assertEquals(10L, service.countTopLevelEnderChest(player));
        assertEquals(10L, countGoldShulkerMoney(player.getEnderChest().getItem(0)));
    }

    @Test
    void deliverHandlesHugeAmountsByMaterializingOnlyWhatFits() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        PlayerMock player = server.addPlayer();
        long amount = 100_000_000_000L;

        LiveMoneyService.DeliveryResult result = service.deliver(
                player,
                amount,
                List.of(MoneyRouteTarget.INVENTORY, MoneyRouteTarget.ENDER_CHEST, MoneyRouteTarget.CUSTODIAL_ACCOUNT)
        );

        long delivered = result.deliveredToInventory() + result.deliveredToEnder();
        assertTrue(delivered > 0L);
        assertEquals(amount, delivered + result.remainder());
        assertTrue(service.scanPlayerMoney(player) > 0L);
    }

    @Test
    void planManagedEnderWalletSyncHandlesHugeTargetAmountsWithoutMaterializingEverything() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        ItemStack[] currentContents = new ItemStack[2];
        long targetAmount = 100_000_000_000L;

        LiveMoneyService.ManagedEnderWalletSyncPlan plan = service.planManagedEnderWalletSync(currentContents, targetAmount);

        long placed = denominationService.countStacks(Arrays.asList(plan.targetContents()));
        assertTrue(placed > 0L);
        assertEquals(targetAmount, placed + plan.overflow());
    }

    @Test
    void maxDeliverableToInventoryFindsBestSingleSlotWithdrawalValue() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        PlayerMock player = server.addPlayer();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        player.getInventory().setItem(0, null);

        long maxDeliverable = service.maxDeliverableToInventory(player, 128L);

        assertEquals(126L, maxDeliverable);
    }

    @Test
    void maxDeliverableToInventoryUsesExistingPartialMoneyStacks() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        PlayerMock player = server.addPlayer();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        player.getInventory().setItem(5, new ItemStack(Material.GOLD_INGOT, 60));

        long maxDeliverable = service.maxDeliverableToInventory(player, 100L);

        assertEquals(36L, maxDeliverable);
    }

    @Test
    void maxDeliverableToInventoryFallsThroughToSmallerDenominationsWhenLargestDoesNotFitRequestedAmount() {
        LiveMoneyService service = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );
        PlayerMock player = server.addPlayer();
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        player.getInventory().setItem(0, null);

        long maxDeliverable = service.maxDeliverableToInventory(player, 80L);

        assertEquals(72L, maxDeliverable);
    }

    private ItemStack goldShulkerWithContents(ItemStack... contents) {
        ItemStack shulker = new ItemStack(Material.YELLOW_SHULKER_BOX, 1);
        BlockStateMeta meta = (BlockStateMeta) shulker.getItemMeta();
        ShulkerBox shulkerBox = (ShulkerBox) meta.getBlockState();
        ItemStack[] clonedContents = new ItemStack[shulkerBox.getInventory().getSize()];
        for (int index = 0; index < contents.length; index++) {
            clonedContents[index] = contents[index] == null ? null : contents[index].clone();
        }
        shulkerBox.getInventory().setContents(clonedContents);
        meta.setBlockState(shulkerBox);
        shulker.setItemMeta(meta);
        return shulker;
    }

    private long countGoldShulkerMoney(ItemStack shulkerItem) {
        if (shulkerItem == null || shulkerItem.getType() != Material.YELLOW_SHULKER_BOX) {
            return 0L;
        }
        BlockStateMeta meta = (BlockStateMeta) shulkerItem.getItemMeta();
        ShulkerBox shulkerBox = (ShulkerBox) meta.getBlockState();
        return denominationService.countStacks(Arrays.asList(shulkerBox.getInventory().getContents()));
    }

    private long countTopLevelMoney(ItemStack[] contents) {
        return denominationService.countStacks(Arrays.asList(contents));
    }
}

