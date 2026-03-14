package com.earthpol.economyPol.service;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.AccountType;
import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.model.IncomingPaymentDeliveryPreference;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import com.earthpol.economyPol.economy.model.PlayerAccountPolicy;
import com.earthpol.economyPol.economy.repository.AccountRepository;
import com.earthpol.economyPol.economy.repository.FundsRepository;
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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class EconomyServiceTest {

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
    void withdrawCustodialAsPhysicalMoneyRestoresLiveContainersWhenDeliveryThrows() {
        PlayerMock player = server.addPlayer();
        player.getInventory().setItem(0, new ItemStack(Material.STONE, 64));
        player.getEnderChest().setItem(1, new ItemStack(Material.DIAMOND, 3));
        player.getInventory().setItemInOffHand(new ItemStack(Material.DIRT, 1));

        AccountRepository accountRepository = mock(AccountRepository.class);
        PlayerRepository playerRepository = mock(PlayerRepository.class);
        FundsRepository fundsRepository = mock(FundsRepository.class);
        DenominationService denominationService = mock(DenominationService.class);
        LiveMoneyService liveMoneyService = mock(LiveMoneyService.class);
        EnderWalletService enderWalletService = mock(EnderWalletService.class);
        PlayerMoneyLockService playerMoneyLockService = mock(PlayerMoneyLockService.class);
        ReservationService reservationService = mock(ReservationService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);
        PluginSettings settings = mock(PluginSettings.class);
        EnhancedLogger operationsLog = mock(EnhancedLogger.class);
        EnhancedLogger auditLog = mock(EnhancedLogger.class);

        PlayerAccountPolicy policy = new PlayerAccountPolicy(false, true, true);
        List<MoneyRouteTarget> routingOrder = List.of(
                MoneyRouteTarget.INVENTORY,
                MoneyRouteTarget.ENDER_CHEST,
                MoneyRouteTarget.CUSTODIAL_ACCOUNT
        );
        UUID accountId = player.getUniqueId();
        AccountRecord account = new AccountRecord(accountId, AccountType.PLAYER, accountId, player.getName(), policy);

        when(settings.playerPolicy()).thenReturn(policy);
        when(settings.changeOverflowPolicy()).thenReturn(PluginSettings.ChangeOverflowPolicy.FAIL);
        when(accountRepository.ensurePlayerAccount(player.getUniqueId(), player.getName(), policy)).thenReturn(account);
        when(fundsRepository.getBalance(accountId)).thenReturn(new BalanceRecord(100L, 0L));
        when(fundsRepository.reserveAvailable(accountId, 10L, "SELF_WITHDRAW_PENDING")).thenReturn(new BalanceRecord(90L, 10L));

        LiveMoneyService.LiveContainerSnapshot snapshot = snapshotOf(player);
        when(liveMoneyService.captureLiveContainerSnapshot(player)).thenReturn(snapshot);
        doAnswer(invocation -> {
            Player target = invocation.getArgument(0);
            target.getInventory().clear();
            target.getEnderChest().clear();
            target.getInventory().setItem(0, new ItemStack(Material.GOLD_NUGGET, 1));
            target.getInventory().setItemInOffHand(new ItemStack(Material.GOLD_INGOT, 1));
            throw new IllegalStateException("boom");
        }).when(liveMoneyService).deliver(player, 10L, routingOrder);
        doAnswer(invocation -> {
            Player target = invocation.getArgument(0);
            LiveMoneyService.LiveContainerSnapshot restoreSnapshot = invocation.getArgument(1);
            target.getInventory().setContents(cloneContents(restoreSnapshot.inventoryContents()));
            target.getEnderChest().setContents(cloneContents(restoreSnapshot.enderChestContents()));
            target.getInventory().setItemInOffHand(cloneStack(restoreSnapshot.offHand()));
            return null;
        }).when(liveMoneyService).restoreLiveContainerSnapshot(player, snapshot);
        doAnswer(invocation -> Optional.ofNullable(((Supplier<?>) invocation.getArgument(1)).get()))
                .when(schedulerService)
                .callOnPlayerEntityScheduler(eq(player), any(), anyString());

        EconomyService economyService = new EconomyService(
                accountRepository,
                playerRepository,
                fundsRepository,
                denominationService,
                liveMoneyService,
                enderWalletService,
                playerMoneyLockService,
                reservationService,
                notificationService,
                schedulerService,
                settings,
                operationsLog,
                auditLog
        );

        MoneyOperationResult result = economyService.withdrawCustodialAsPhysicalMoney(player, 10L);

        assertFalse(result.success());
        assertEquals(10L, result.remainder());
        assertEquals("Physical withdrawal failed while delivering money.", result.message());
        assertEquals(Material.STONE, player.getInventory().getItem(0).getType());
        assertEquals(Material.DIAMOND, player.getEnderChest().getItem(1).getType());
        assertEquals(Material.DIRT, player.getInventory().getItemInOffHand().getType());
        verify(fundsRepository).reserveAvailable(accountId, 10L, "SELF_WITHDRAW_PENDING");
        verify(fundsRepository).releaseReserved(accountId, 10L, "SELF_WITHDRAW_ROLLBACK");
        verify(fundsRepository, never()).settleReservedWithdrawal(any(), anyLong(), anyLong(), anyString(), anyString(), any());
        verifyNoInteractions(notificationService);
    }

    @Test
    void withdrawCustodialAsPhysicalMoneySettlesReserveAndKeepsRemainderInCustodial() {
        PlayerMock player = server.addPlayer();

        AccountRepository accountRepository = mock(AccountRepository.class);
        PlayerRepository playerRepository = mock(PlayerRepository.class);
        FundsRepository fundsRepository = mock(FundsRepository.class);
        DenominationService denominationService = mock(DenominationService.class);
        LiveMoneyService liveMoneyService = mock(LiveMoneyService.class);
        EnderWalletService enderWalletService = mock(EnderWalletService.class);
        PlayerMoneyLockService playerMoneyLockService = mock(PlayerMoneyLockService.class);
        ReservationService reservationService = mock(ReservationService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);
        PluginSettings settings = mock(PluginSettings.class);
        EnhancedLogger operationsLog = mock(EnhancedLogger.class);
        EnhancedLogger auditLog = mock(EnhancedLogger.class);

        PlayerAccountPolicy policy = new PlayerAccountPolicy(false, true, true);
        List<MoneyRouteTarget> routingOrder = List.of(
                MoneyRouteTarget.INVENTORY,
                MoneyRouteTarget.ENDER_CHEST,
                MoneyRouteTarget.CUSTODIAL_ACCOUNT
        );
        UUID accountId = player.getUniqueId();
        AccountRecord account = new AccountRecord(accountId, AccountType.PLAYER, accountId, player.getName(), policy);

        when(settings.playerPolicy()).thenReturn(policy);
        when(settings.changeOverflowPolicy()).thenReturn(PluginSettings.ChangeOverflowPolicy.FAIL);
        when(accountRepository.ensurePlayerAccount(player.getUniqueId(), player.getName(), policy)).thenReturn(account);
        when(fundsRepository.getBalance(accountId)).thenReturn(new BalanceRecord(100L, 0L));
        when(fundsRepository.reserveAvailable(accountId, 10L, "SELF_WITHDRAW_PENDING")).thenReturn(new BalanceRecord(90L, 10L));
        when(fundsRepository.settleReservedWithdrawal(
                accountId,
                6L,
                4L,
                "SELF_WITHDRAW_CAPTURE",
                "SELF_WITHDRAW_REMAINDER",
                player.getUniqueId()
        )).thenReturn(new BalanceRecord(94L, 0L));
        when(liveMoneyService.captureLiveContainerSnapshot(player)).thenReturn(snapshotOf(player));
        when(liveMoneyService.deliver(player, 10L, routingOrder)).thenReturn(new LiveMoneyService.DeliveryResult(6L, 0L, 4L));
        doAnswer(invocation -> Optional.ofNullable(((Supplier<?>) invocation.getArgument(1)).get()))
                .when(schedulerService)
                .callOnPlayerEntityScheduler(eq(player), any(), anyString());

        EconomyService economyService = new EconomyService(
                accountRepository,
                playerRepository,
                fundsRepository,
                denominationService,
                liveMoneyService,
                enderWalletService,
                playerMoneyLockService,
                reservationService,
                notificationService,
                schedulerService,
                settings,
                operationsLog,
                auditLog
        );

        MoneyOperationResult result = economyService.withdrawCustodialAsPhysicalMoney(player, 10L);

        assertTrue(result.success());
        assertEquals(10L, result.requestedAmount());
        assertEquals(6L, result.processedAmount());
        assertEquals(4L, result.remainder());
        verify(fundsRepository).reserveAvailable(accountId, 10L, "SELF_WITHDRAW_PENDING");
        verify(fundsRepository).settleReservedWithdrawal(
                accountId,
                6L,
                4L,
                "SELF_WITHDRAW_CAPTURE",
                "SELF_WITHDRAW_REMAINDER",
                player.getUniqueId()
        );
        verify(fundsRepository, never()).releaseReserved(any(), anyLong(), anyString());
        verify(notificationService).notifyWithdrawalRetainedInCustodial(player, 4L, 94L);
    }

    @Test
    void withdrawPlayerSendsDirectChangeNotificationForVaultTriggeredFailures() {
        PlayerMock player = server.addPlayer();

        AccountRepository accountRepository = mock(AccountRepository.class);
        PlayerRepository playerRepository = mock(PlayerRepository.class);
        FundsRepository fundsRepository = mock(FundsRepository.class);
        DenominationService denominationService = mock(DenominationService.class);
        LiveMoneyService liveMoneyService = mock(LiveMoneyService.class);
        EnderWalletService enderWalletService = mock(EnderWalletService.class);
        PlayerMoneyLockService playerMoneyLockService = mock(PlayerMoneyLockService.class);
        ReservationService reservationService = mock(ReservationService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);
        PluginSettings settings = mock(PluginSettings.class);
        EnhancedLogger operationsLog = mock(EnhancedLogger.class);
        EnhancedLogger auditLog = mock(EnhancedLogger.class);

        PlayerAccountPolicy policy = new PlayerAccountPolicy(false, true, true);
        List<MoneyRouteTarget> routingOrder = List.of(
                MoneyRouteTarget.INVENTORY,
                MoneyRouteTarget.ENDER_CHEST,
                MoneyRouteTarget.CUSTODIAL_ACCOUNT
        );
        UUID accountId = player.getUniqueId();
        AccountRecord account = new AccountRecord(accountId, AccountType.PLAYER, accountId, player.getName(), policy);

        when(settings.playerPolicy()).thenReturn(policy);
        when(settings.changeOverflowPolicy()).thenReturn(PluginSettings.ChangeOverflowPolicy.FAIL);
        when(accountRepository.ensurePlayerAccount(player.getUniqueId(), player.getName(), policy)).thenReturn(account);
        when(playerMoneyLockService.isLocked(player.getUniqueId())).thenReturn(false);
        when(liveMoneyService.captureLiveContainerSnapshot(player)).thenReturn(snapshotOf(player));
        when(liveMoneyService.spendFromLiveSources(player, 10L, routingOrder, PluginSettings.ChangeOverflowPolicy.FAIL))
                .thenReturn(LiveMoneyService.SpendResult.failure(10L, LiveMoneyService.NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE));
        doAnswer(invocation -> Optional.ofNullable(((Supplier<?>) invocation.getArgument(1)).get()))
                .when(schedulerService)
                .callOnPlayerEntityScheduler(eq(player), any(), anyString());

        EconomyService economyService = new EconomyService(
                accountRepository,
                playerRepository,
                fundsRepository,
                denominationService,
                liveMoneyService,
                enderWalletService,
                playerMoneyLockService,
                reservationService,
                notificationService,
                schedulerService,
                settings,
                operationsLog,
                auditLog
        );

        MoneyOperationResult result = economyService.withdrawPlayer(player, 10L, "VAULT2_WITHDRAW:QuickShop");

        assertFalse(result.success());
        assertEquals(LiveMoneyService.NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE, result.message());
        verify(notificationService).notifyNotEnoughRoomForChange(player);
    }

    @Test
    void withdrawPlayerSendsDirectChangeNotificationForNonVaultFailures() {
        PlayerMock player = server.addPlayer();

        AccountRepository accountRepository = mock(AccountRepository.class);
        PlayerRepository playerRepository = mock(PlayerRepository.class);
        FundsRepository fundsRepository = mock(FundsRepository.class);
        DenominationService denominationService = mock(DenominationService.class);
        LiveMoneyService liveMoneyService = mock(LiveMoneyService.class);
        EnderWalletService enderWalletService = mock(EnderWalletService.class);
        PlayerMoneyLockService playerMoneyLockService = mock(PlayerMoneyLockService.class);
        ReservationService reservationService = mock(ReservationService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);
        PluginSettings settings = mock(PluginSettings.class);
        EnhancedLogger operationsLog = mock(EnhancedLogger.class);
        EnhancedLogger auditLog = mock(EnhancedLogger.class);

        PlayerAccountPolicy policy = new PlayerAccountPolicy(false, true, true);
        List<MoneyRouteTarget> routingOrder = List.of(
                MoneyRouteTarget.INVENTORY,
                MoneyRouteTarget.ENDER_CHEST,
                MoneyRouteTarget.CUSTODIAL_ACCOUNT
        );
        UUID accountId = player.getUniqueId();
        AccountRecord account = new AccountRecord(accountId, AccountType.PLAYER, accountId, player.getName(), policy);

        when(settings.playerPolicy()).thenReturn(policy);
        when(settings.changeOverflowPolicy()).thenReturn(PluginSettings.ChangeOverflowPolicy.FAIL);
        when(accountRepository.ensurePlayerAccount(player.getUniqueId(), player.getName(), policy)).thenReturn(account);
        when(playerMoneyLockService.isLocked(player.getUniqueId())).thenReturn(false);
        when(liveMoneyService.captureLiveContainerSnapshot(player)).thenReturn(snapshotOf(player));
        when(liveMoneyService.spendFromLiveSources(player, 10L, routingOrder, PluginSettings.ChangeOverflowPolicy.FAIL))
                .thenReturn(LiveMoneyService.SpendResult.failure(10L, LiveMoneyService.NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE));
        doAnswer(invocation -> Optional.ofNullable(((Supplier<?>) invocation.getArgument(1)).get()))
                .when(schedulerService)
                .callOnPlayerEntityScheduler(eq(player), any(), anyString());

        EconomyService economyService = new EconomyService(
                accountRepository,
                playerRepository,
                fundsRepository,
                denominationService,
                liveMoneyService,
                enderWalletService,
                playerMoneyLockService,
                reservationService,
                notificationService,
                schedulerService,
                settings,
                operationsLog,
                auditLog
        );

        MoneyOperationResult result = economyService.withdrawPlayer(player, 10L, "PLAYER_MARKET_BUY");

        assertFalse(result.success());
        assertEquals(LiveMoneyService.NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE, result.message());
        verify(notificationService).notifyNotEnoughRoomForChange(player);
    }

    @Test
    void hasEnoughInFailModeUsesLiveSpendabilityWorkaroundAndNotifiesForNoChangeRoom() {
        PlayerMock player = server.addPlayer();

        AccountRepository accountRepository = mock(AccountRepository.class);
        PlayerRepository playerRepository = mock(PlayerRepository.class);
        FundsRepository fundsRepository = mock(FundsRepository.class);
        DenominationService denominationService = mock(DenominationService.class);
        LiveMoneyService liveMoneyService = mock(LiveMoneyService.class);
        EnderWalletService enderWalletService = mock(EnderWalletService.class);
        PlayerMoneyLockService playerMoneyLockService = mock(PlayerMoneyLockService.class);
        ReservationService reservationService = mock(ReservationService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);
        PluginSettings settings = mock(PluginSettings.class);
        EnhancedLogger operationsLog = mock(EnhancedLogger.class);
        EnhancedLogger auditLog = mock(EnhancedLogger.class);

        List<MoneyRouteTarget> routingOrder = List.of(
                MoneyRouteTarget.INVENTORY,
                MoneyRouteTarget.ENDER_CHEST,
                MoneyRouteTarget.CUSTODIAL_ACCOUNT
        );

        when(settings.changeOverflowPolicy()).thenReturn(PluginSettings.ChangeOverflowPolicy.FAIL);
        when(playerMoneyLockService.isLocked(player.getUniqueId())).thenReturn(false);
        when(liveMoneyService.canSpendFromLiveSources(player, 10L, routingOrder, PluginSettings.ChangeOverflowPolicy.FAIL))
                .thenReturn(LiveMoneyService.SpendabilityResult.blocked(LiveMoneyService.NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE));
        doAnswer(invocation -> Optional.ofNullable(((Supplier<?>) invocation.getArgument(1)).get()))
                .when(schedulerService)
                .callOnPlayerEntityScheduler(eq(player), any(), anyString());

        EconomyService economyService = new EconomyService(
                accountRepository,
                playerRepository,
                fundsRepository,
                denominationService,
                liveMoneyService,
                enderWalletService,
                playerMoneyLockService,
                reservationService,
                notificationService,
                schedulerService,
                settings,
                operationsLog,
                auditLog
        );

        boolean hasEnough = economyService.hasEnough(player, 10L);

        assertFalse(hasEnough);
        verify(notificationService).notifyNotEnoughRoomForChange(player);
    }

    @Test
    void hasEnoughInCustodialModeFallsBackToBalanceComparison() {
        PlayerMock player = server.addPlayer();

        AccountRepository accountRepository = mock(AccountRepository.class);
        PlayerRepository playerRepository = mock(PlayerRepository.class);
        FundsRepository fundsRepository = mock(FundsRepository.class);
        DenominationService denominationService = mock(DenominationService.class);
        LiveMoneyService liveMoneyService = mock(LiveMoneyService.class);
        EnderWalletService enderWalletService = mock(EnderWalletService.class);
        PlayerMoneyLockService playerMoneyLockService = mock(PlayerMoneyLockService.class);
        ReservationService reservationService = mock(ReservationService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);
        PluginSettings settings = mock(PluginSettings.class);
        EnhancedLogger operationsLog = mock(EnhancedLogger.class);
        EnhancedLogger auditLog = mock(EnhancedLogger.class);
        PlayerAccountPolicy policy = new PlayerAccountPolicy(false, true, true);
        UUID accountId = player.getUniqueId();
        AccountRecord account = new AccountRecord(accountId, AccountType.PLAYER, accountId, player.getName(), policy);

        when(settings.playerPolicy()).thenReturn(policy);
        when(settings.changeOverflowPolicy()).thenReturn(PluginSettings.ChangeOverflowPolicy.CUSTODIAL);
        when(accountRepository.ensurePlayerAccount(player.getUniqueId(), player.getName(), policy)).thenReturn(account);
        when(fundsRepository.getBalance(accountId)).thenReturn(new BalanceRecord(0L, 0L));
        doAnswer(invocation -> Optional.ofNullable(((Supplier<?>) invocation.getArgument(1)).get()))
                .when(schedulerService)
                .callOnPlayerEntityScheduler(eq(player), any(), anyString());
        when(liveMoneyService.scanPlayerMoney(player)).thenReturn(50L);

        EconomyService economyService = new EconomyService(
                accountRepository,
                playerRepository,
                fundsRepository,
                denominationService,
                liveMoneyService,
                enderWalletService,
                playerMoneyLockService,
                reservationService,
                notificationService,
                schedulerService,
                settings,
                operationsLog,
                auditLog
        );

        boolean hasEnough = economyService.hasEnough(player, 10L);

        assertTrue(hasEnough);
        verifyNoInteractions(notificationService);
        verify(liveMoneyService, never()).canSpendFromLiveSources(any(), anyLong(), any(), any());
    }

    @Test
    void withdrawPlayerRoutesUnplaceableChangeToCustodialWhenConfigured() {
        PlayerMock player = server.addPlayer();

        AccountRepository accountRepository = mock(AccountRepository.class);
        PlayerRepository playerRepository = mock(PlayerRepository.class);
        FundsRepository fundsRepository = mock(FundsRepository.class);
        DenominationService denominationService = mock(DenominationService.class);
        LiveMoneyService liveMoneyService = mock(LiveMoneyService.class);
        EnderWalletService enderWalletService = mock(EnderWalletService.class);
        PlayerMoneyLockService playerMoneyLockService = mock(PlayerMoneyLockService.class);
        ReservationService reservationService = mock(ReservationService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);
        PluginSettings settings = mock(PluginSettings.class);
        EnhancedLogger operationsLog = mock(EnhancedLogger.class);
        EnhancedLogger auditLog = mock(EnhancedLogger.class);

        PlayerAccountPolicy policy = new PlayerAccountPolicy(false, true, true);
        List<MoneyRouteTarget> routingOrder = List.of(
                MoneyRouteTarget.INVENTORY,
                MoneyRouteTarget.ENDER_CHEST,
                MoneyRouteTarget.CUSTODIAL_ACCOUNT
        );
        UUID accountId = player.getUniqueId();
        AccountRecord account = new AccountRecord(accountId, AccountType.PLAYER, accountId, player.getName(), policy);
        LiveMoneyService.LiveContainerSnapshot snapshot = snapshotOf(player);

        when(settings.playerPolicy()).thenReturn(policy);
        when(settings.changeOverflowPolicy()).thenReturn(PluginSettings.ChangeOverflowPolicy.CUSTODIAL);
        when(accountRepository.ensurePlayerAccount(player.getUniqueId(), player.getName(), policy)).thenReturn(account);
        when(accountRepository.ensurePlayerAccount(player.getUniqueId(), player.getName(), policy)).thenReturn(account);
        when(playerMoneyLockService.isLocked(player.getUniqueId())).thenReturn(false);
        when(liveMoneyService.captureLiveContainerSnapshot(player)).thenReturn(snapshot);
        when(liveMoneyService.spendFromLiveSources(player, 10L, routingOrder, PluginSettings.ChangeOverflowPolicy.CUSTODIAL))
                .thenReturn(LiveMoneyService.SpendResult.success(10L, 81L, 71L, 71L));
        when(fundsRepository.changeAvailable(
                accountId,
                71L,
                "CUSTODIAL_CREDIT",
                "LIVE_CHANGE_OVERFLOW",
                player.getUniqueId(),
                null
        )).thenReturn(new BalanceRecord(71L, 0L));
        doAnswer(invocation -> Optional.ofNullable(((Supplier<?>) invocation.getArgument(1)).get()))
                .when(schedulerService)
                .callOnPlayerEntityScheduler(eq(player), any(), anyString());

        EconomyService economyService = new EconomyService(
                accountRepository,
                playerRepository,
                fundsRepository,
                denominationService,
                liveMoneyService,
                enderWalletService,
                playerMoneyLockService,
                reservationService,
                notificationService,
                schedulerService,
                settings,
                operationsLog,
                auditLog
        );

        MoneyOperationResult result = economyService.withdrawPlayer(player, 10L, "VAULT2_WITHDRAW:Towny");

        assertTrue(result.success());
        assertEquals(10L, result.processedAmount());
        verify(notificationService).notifyChangeRoutedToCustodial(player, 71L, 71L);
        verify(notificationService, never()).notifyNotEnoughRoomForChange(player);
    }

    @Test
    void depositPlayerUsesIncomingPaymentDeliveryPreferenceForPassiveRouting() {
        PlayerMock player = server.addPlayer();

        AccountRepository accountRepository = mock(AccountRepository.class);
        PlayerRepository playerRepository = mock(PlayerRepository.class);
        FundsRepository fundsRepository = mock(FundsRepository.class);
        DenominationService denominationService = mock(DenominationService.class);
        LiveMoneyService liveMoneyService = mock(LiveMoneyService.class);
        EnderWalletService enderWalletService = mock(EnderWalletService.class);
        PlayerMoneyLockService playerMoneyLockService = mock(PlayerMoneyLockService.class);
        ReservationService reservationService = mock(ReservationService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);
        PluginSettings settings = mock(PluginSettings.class);
        EnhancedLogger operationsLog = mock(EnhancedLogger.class);
        EnhancedLogger auditLog = mock(EnhancedLogger.class);

        PlayerAccountPolicy policy = new PlayerAccountPolicy(false, true, true);
        UUID accountId = player.getUniqueId();
        AccountRecord account = new AccountRecord(accountId, AccountType.PLAYER, accountId, player.getName(), policy);
        List<MoneyRouteTarget> expectedRoutingOrder = List.of(
                MoneyRouteTarget.ENDER_CHEST,
                MoneyRouteTarget.CUSTODIAL_ACCOUNT
        );

        when(settings.playerPolicy()).thenReturn(policy);
        when(accountRepository.ensurePlayerAccount(player.getUniqueId(), player.getName(), policy)).thenReturn(account);
        when(playerRepository.getIncomingPaymentDeliveryPreference(player.getUniqueId()))
                .thenReturn(IncomingPaymentDeliveryPreference.SKIP_INVENTORY);
        when(liveMoneyService.deliver(player, 10L, expectedRoutingOrder))
                .thenReturn(new LiveMoneyService.DeliveryResult(0L, 10L, 0L));
        doAnswer(invocation -> Optional.ofNullable(((Supplier<?>) invocation.getArgument(1)).get()))
                .when(schedulerService)
                .callOnPlayerEntityScheduler(eq(player), any(), anyString());

        EconomyService economyService = new EconomyService(
                accountRepository,
                playerRepository,
                fundsRepository,
                denominationService,
                liveMoneyService,
                enderWalletService,
                playerMoneyLockService,
                reservationService,
                notificationService,
                schedulerService,
                settings,
                operationsLog,
                auditLog
        );

        MoneyOperationResult result = economyService.depositPlayer(player, 10L, "VAULT2_DEPOSIT:QuickShop-Hikari");

        assertTrue(result.success());
        verify(liveMoneyService).deliver(player, 10L, expectedRoutingOrder);
        verify(fundsRepository, never()).changeAvailable(any(), anyLong(), anyString(), anyString(), any(), isNull());
        verifyNoInteractions(notificationService);
    }

    private static LiveMoneyService.LiveContainerSnapshot snapshotOf(Player player) {
        return new LiveMoneyService.LiveContainerSnapshot(
                cloneContents(player.getInventory().getContents()),
                cloneContents(player.getEnderChest().getContents()),
                cloneStack(player.getInventory().getItemInOffHand())
        );
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] clone = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            clone[index] = cloneStack(contents[index]);
        }
        return clone;
    }

    private static ItemStack cloneStack(ItemStack itemStack) {
        return itemStack == null ? null : itemStack.clone();
    }
}
