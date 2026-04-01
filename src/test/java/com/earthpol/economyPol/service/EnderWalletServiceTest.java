package com.earthpol.economyPol.service;

import com.earthpol.economyPol.EconomyPol;
import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.repository.EnderWalletRepository;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.money.LiveMoneyService;
import com.earthpol.economyPol.economy.service.player.EnderWalletService;
import com.earthpol.economyPol.economy.service.player.NotificationService;
import com.earthpol.economyPol.economy.service.player.PlayerMoneyLockService;
import com.earthpol.economyPol.economy.service.support.SchedulerService;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class EnderWalletServiceTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void normalizeOnlineEnderWalletRestoresEnderChestWhenCustodialCreditFails() {
        PlayerMock player = server.addPlayer();
        player.getEnderChest().setItem(0, new ItemStack(Material.GOLD_BLOCK, 1));

        EconomyPol plugin = mock(EconomyPol.class);
        EconomyService economyService = mock(EconomyService.class);
        LiveMoneyService liveMoneyService = mock(LiveMoneyService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);

        when(plugin.economyService()).thenReturn(economyService);
        when(economyService.liveMoneyService()).thenReturn(liveMoneyService);
        when(schedulerService.runOnPlayerEntityScheduler(eq(player), any(Runnable.class), eq("ender-wallet-normalize")))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return true;
                });
        when(liveMoneyService.normalizeEnderChest(player)).thenAnswer(invocation -> {
            player.getEnderChest().clear();
            return new LiveMoneyService.NormalizationResult(81L, 81L, false);
        });
        when(economyService.creditCustodial(
                eq(player.getUniqueId()),
                eq(player.getName()),
                eq(81L),
                eq("ENDER_WALLET_NORMALIZE_OVERFLOW")
        )).thenThrow(new RuntimeException("db down"));

        EnderWalletService service = new EnderWalletService(
                plugin,
                mock(EnderWalletRepository.class),
                new PlayerMoneyLockService(),
                notificationService,
                schedulerService,
                mock(EconomyLoggers.class),
                false
        );

        service.normalizeOnlineEnderWallet(player);

        assertEquals(Material.GOLD_BLOCK, player.getEnderChest().getItem(0).getType());
        assertEquals(1, player.getEnderChest().getItem(0).getAmount());
        verify(notificationService, never()).notifyWalletOverflowToCustodial(any(), anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void normalizeOnlineEnderWalletDoesNotRestoreEnderChestAfterNotificationFailure() {
        PlayerMock player = server.addPlayer();
        player.getEnderChest().setItem(0, new ItemStack(Material.GOLD_BLOCK, 1));

        EconomyPol plugin = mock(EconomyPol.class);
        EconomyService economyService = mock(EconomyService.class);
        LiveMoneyService liveMoneyService = mock(LiveMoneyService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);

        when(plugin.economyService()).thenReturn(economyService);
        when(economyService.liveMoneyService()).thenReturn(liveMoneyService);
        when(schedulerService.runOnPlayerEntityScheduler(eq(player), any(Runnable.class), eq("ender-wallet-normalize")))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return true;
                });
        when(liveMoneyService.normalizeEnderChest(player)).thenAnswer(invocation -> {
            player.getEnderChest().clear();
            return new LiveMoneyService.NormalizationResult(81L, 81L, false);
        });
        when(economyService.creditCustodial(
                eq(player.getUniqueId()),
                eq(player.getName()),
                eq(81L),
                eq("ENDER_WALLET_NORMALIZE_OVERFLOW")
        )).thenReturn(new BalanceRecord(81L, 0L));
        doThrow(new RuntimeException("notify failed"))
                .when(notificationService)
                .notifyWalletOverflowToCustodial(player, 81L, 81L, false);

        EnderWalletService service = new EnderWalletService(
                plugin,
                mock(EnderWalletRepository.class),
                new PlayerMoneyLockService(),
                notificationService,
                schedulerService,
                mock(EconomyLoggers.class),
                false
        );

        service.normalizeOnlineEnderWallet(player);

        assertNull(player.getEnderChest().getItem(0));
        verify(economyService).creditCustodial(player.getUniqueId(), player.getName(), 81L, "ENDER_WALLET_NORMALIZE_OVERFLOW");
    }
}
