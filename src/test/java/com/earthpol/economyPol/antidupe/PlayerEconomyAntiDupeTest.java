package com.earthpol.economyPol.antidupe;

import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.AccountType;
import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.model.Denomination;
import com.earthpol.economyPol.economy.model.IncomingPaymentDeliveryPreference;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import com.earthpol.economyPol.economy.repository.AccountRepository;
import com.earthpol.economyPol.economy.repository.FundsRepository;
import com.earthpol.economyPol.economy.repository.PendingPlayerPaymentRepository;
import com.earthpol.economyPol.economy.repository.PlayerRepository;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.money.LiveMoneyService;
import com.earthpol.economyPol.economy.service.player.EnderWalletService;
import com.earthpol.economyPol.economy.service.player.NotificationService;
import com.earthpol.economyPol.economy.service.player.PlayerMoneyLockService;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import com.earthpol.economyPol.economy.service.support.ReservationService;
import com.earthpol.economyPol.economy.service.support.SchedulerService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class PlayerEconomyAntiDupeTest {

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
    void withdrawPlayerFailModeDoesNotCreateOrDestroyValueWhenChangeCannotFit() {
        PlayerMock player = server.addPlayer();
        fillInventoryAndEnderWithStone(player);
        player.getInventory().setItem(0, new ItemStack(Material.GOLD_BLOCK, 1));

        Harness harness = createHarness(PluginSettings.ChangeOverflowPolicy.FAIL, 0L);
        long initialAssets = totalAssets(player, harness);

        MoneyOperationResult result = harness.economyService()
                .withdrawPlayer(player, 10L, "VAULT2_WITHDRAW:QuickShop-Hikari");

        assertFalse(result.success());
        assertEquals(LiveMoneyService.NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE, result.message());
        assertEquals(initialAssets, totalAssets(player, harness));
        assertEquals(81L, harness.liveMoneyService().scanPlayerMoney(player));
        assertEquals(0L, harness.availableBalance().get());
        assertEquals(0L, harness.reservedBalance().get());
    }

    @Test
    void withdrawPlayerWithCustodialOverflowStillConservesNetValue() {
        PlayerMock player = server.addPlayer();
        fillInventoryAndEnderWithStone(player);
        player.getInventory().setItem(0, new ItemStack(Material.GOLD_BLOCK, 1));

        Harness harness = createHarness(PluginSettings.ChangeOverflowPolicy.CUSTODIAL, 0L);
        long initialAssets = totalAssets(player, harness);

        MoneyOperationResult result = harness.economyService()
                .withdrawPlayer(player, 10L, "VAULT2_WITHDRAW:QuickShop-Hikari");

        assertTrue(result.success());
        assertEquals(initialAssets - 10L, totalAssets(player, harness));
        assertEquals(63L, harness.liveMoneyService().scanPlayerMoney(player));
        assertEquals(8L, harness.availableBalance().get());
        assertEquals(0L, harness.reservedBalance().get());
    }

    @Test
    void depositSelfWithOverflowStillConservesTotalAssets() {
        PlayerMock player = server.addPlayer();
        fillInventoryAndEnderWithStone(player);
        player.getInventory().setItem(0, new ItemStack(Material.GOLD_BLOCK, 1));

        Harness harness = createHarness(PluginSettings.ChangeOverflowPolicy.CUSTODIAL, 0L);
        long initialAssets = totalAssets(player, harness);

        MoneyOperationResult result = harness.economyService().depositSelf(player, 10L);

        assertTrue(result.success());
        assertEquals(18L, result.processedAmount());
        assertEquals(initialAssets, totalAssets(player, harness));
        assertEquals(63L, harness.liveMoneyService().scanPlayerMoney(player));
        assertEquals(18L, harness.availableBalance().get());
        assertEquals(0L, harness.reservedBalance().get());
    }

    @Test
    void explicitCustodialWithdrawalWithLimitedSpaceDoesNotDuplicateValue() {
        PlayerMock player = server.addPlayer();
        fillInventoryAndEnderWithStone(player);
        player.getInventory().setItem(0, null);

        Harness harness = createHarness(PluginSettings.ChangeOverflowPolicy.CUSTODIAL, 100L);
        long initialAssets = totalAssets(player, harness);

        MoneyOperationResult result = harness.economyService().withdrawCustodialAsPhysicalMoney(
                player,
                100L,
                List.of(MoneyRouteTarget.INVENTORY, MoneyRouteTarget.ENDER_CHEST)
        );

        assertTrue(result.success());
        assertEquals(initialAssets, totalAssets(player, harness));
        assertEquals(81L, harness.liveMoneyService().scanPlayerMoney(player));
        assertEquals(19L, harness.availableBalance().get());
        assertEquals(0L, harness.reservedBalance().get());
    }

    private Harness createHarness(PluginSettings.ChangeOverflowPolicy changeOverflowPolicy, long initialAvailableBalance) {
        AccountRepository accountRepository = mock(AccountRepository.class);
        PlayerRepository playerRepository = mock(PlayerRepository.class);
        FundsRepository fundsRepository = mock(FundsRepository.class);
        PendingPlayerPaymentRepository pendingPlayerPaymentRepository = mock(PendingPlayerPaymentRepository.class);
        EnderWalletService enderWalletService = mock(EnderWalletService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);
        ReservationService reservationService = mock(ReservationService.class);
        PluginSettings settings = mock(PluginSettings.class);
        EconomyLoggers loggers = mock(EconomyLoggers.class);

        AtomicLong availableBalance = new AtomicLong(initialAvailableBalance);
        AtomicLong reservedBalance = new AtomicLong(0L);

        DenominationService denominationService = new DenominationService(
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
        LiveMoneyService liveMoneyService = new LiveMoneyService(
                denominationService,
                new PluginSettings.WalletSettings(true, true, true)
        );

        when(settings.changeOverflowPolicy()).thenReturn(changeOverflowPolicy);
        when(playerRepository.getIncomingPaymentDeliveryPreference(any(UUID.class)))
                .thenReturn(IncomingPaymentDeliveryPreference.DEFAULT);
        doNothing().when(playerRepository).ensurePlayer(any(UUID.class), any());
        doAnswer(invocation -> {
            UUID playerUuid = invocation.getArgument(0);
            String playerName = invocation.getArgument(1);
            return new AccountRecord(
                    playerUuid,
                    AccountType.PLAYER,
                    playerUuid,
                    playerName == null ? playerUuid.toString() : playerName
            );
        }).when(accountRepository).ensurePlayerAccount(any(UUID.class), any());
        when(accountRepository.findPlayerAccount(any(UUID.class)))
                .thenAnswer(invocation -> {
                    UUID playerUuid = invocation.getArgument(0);
                    return Optional.of(new AccountRecord(
                            playerUuid,
                            AccountType.PLAYER,
                            playerUuid,
                            playerUuid.toString()
                    ));
                });

        when(fundsRepository.getBalance(any(UUID.class)))
                .thenAnswer(invocation -> new BalanceRecord(availableBalance.get(), reservedBalance.get()));
        when(fundsRepository.changeAvailable(any(UUID.class), anyLong(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    long delta = invocation.getArgument(1);
                    long nextAvailable = availableBalance.get() + delta;
                    if (nextAvailable < 0L) {
                        throw new IllegalStateException("Insufficient available balance");
                    }
                    availableBalance.set(nextAvailable);
                    return new BalanceRecord(availableBalance.get(), reservedBalance.get());
                });
        when(fundsRepository.reserveAvailable(any(UUID.class), anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    long amount = invocation.getArgument(1);
                    if (availableBalance.get() < amount) {
                        throw new IllegalStateException("Insufficient available balance to reserve");
                    }
                    availableBalance.addAndGet(-amount);
                    reservedBalance.addAndGet(amount);
                    return new BalanceRecord(availableBalance.get(), reservedBalance.get());
                });
        when(fundsRepository.releaseReserved(any(UUID.class), anyLong(), anyString()))
                .thenAnswer(invocation -> {
                    long amount = invocation.getArgument(1);
                    if (reservedBalance.get() < amount) {
                        throw new IllegalStateException("Insufficient reserved balance to release");
                    }
                    reservedBalance.addAndGet(-amount);
                    availableBalance.addAndGet(amount);
                    return new BalanceRecord(availableBalance.get(), reservedBalance.get());
                });
        when(fundsRepository.settleReservedWithdrawal(any(UUID.class), anyLong(), anyLong(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    long deliveredAmount = invocation.getArgument(1);
                    long releasedAmount = invocation.getArgument(2);
                    long totalSettled = deliveredAmount + releasedAmount;
                    if (reservedBalance.get() < totalSettled) {
                        throw new IllegalStateException("Insufficient reserved balance to settle");
                    }
                    reservedBalance.addAndGet(-totalSettled);
                    availableBalance.addAndGet(releasedAmount);
                    return new BalanceRecord(availableBalance.get(), reservedBalance.get());
                });
        doAnswer(invocation -> Optional.ofNullable(((Supplier<?>) invocation.getArgument(1)).get()))
                .when(schedulerService)
                .callOnPlayerEntityScheduler(any(Player.class), any(), anyString());

        EconomyService economyService = new EconomyService(
                accountRepository,
                playerRepository,
                fundsRepository,
                pendingPlayerPaymentRepository,
                denominationService,
                liveMoneyService,
                enderWalletService,
                new PlayerMoneyLockService(),
                reservationService,
                notificationService,
                schedulerService,
                settings,
                loggers
        );
        return new Harness(economyService, liveMoneyService, availableBalance, reservedBalance);
    }

    private long totalAssets(PlayerMock player, Harness harness) {
        return harness.liveMoneyService().scanPlayerMoney(player)
                + harness.availableBalance().get()
                + harness.reservedBalance().get();
    }

    private void fillInventoryAndEnderWithStone(PlayerMock player) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            player.getInventory().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        for (int slot = 0; slot < player.getEnderChest().getSize(); slot++) {
            player.getEnderChest().setItem(slot, new ItemStack(Material.STONE, 64));
        }
        player.getInventory().setItemInOffHand(new ItemStack(Material.STONE, 1));
    }

    private record Harness(
            EconomyService economyService,
            LiveMoneyService liveMoneyService,
            AtomicLong availableBalance,
            AtomicLong reservedBalance
    ) {}
}
