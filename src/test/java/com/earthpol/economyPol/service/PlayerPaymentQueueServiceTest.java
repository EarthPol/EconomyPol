package com.earthpol.economyPol.service;

import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.AccountType;
import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.model.IncomingPaymentDeliveryPreference;
import com.earthpol.economyPol.economy.model.PendingPlayerPayment;
import com.earthpol.economyPol.economy.model.PendingPlayerPaymentAttemptResult;
import com.earthpol.economyPol.economy.model.PendingPlayerPaymentStatus;
import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.repository.FundsRepository;
import com.earthpol.economyPol.economy.repository.PendingPlayerPaymentRepository;
import com.earthpol.economyPol.economy.service.account.AccountRegistryService;
import com.earthpol.economyPol.economy.service.money.LiveMoneyService;
import com.earthpol.economyPol.economy.service.player.NotificationService;
import com.earthpol.economyPol.economy.service.player.PlayerMoneyLockService;
import com.earthpol.economyPol.economy.service.player.PlayerPaymentQueueService;
import com.earthpol.economyPol.economy.service.support.SchedulerService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class PlayerPaymentQueueServiceTest {

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
    void acceptOnlinePaymentUsesDirectCustodialForCustodialOnlyPreference() {
        PlayerMock player = server.addPlayer();
        AccountRegistryService accountRegistryService = mock(AccountRegistryService.class);
        FundsRepository fundsRepository = mock(FundsRepository.class);
        PendingPlayerPaymentRepository pendingPlayerPaymentRepository = mock(PendingPlayerPaymentRepository.class);
        LiveMoneyService liveMoneyService = mock(LiveMoneyService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);
        EconomyLoggers loggers = mock(EconomyLoggers.class);

        AccountRecord account = new AccountRecord(player.getUniqueId(), AccountType.PLAYER, player.getUniqueId(), player.getName());
        when(accountRegistryService.getIncomingPaymentDeliveryPreference(player.getUniqueId()))
                .thenReturn(IncomingPaymentDeliveryPreference.SKIP_INVENTORY_AND_ENDERCHEST);
        when(accountRegistryService.requirePlayerAccount(player)).thenReturn(account);
        when(fundsRepository.changeAvailable(
                account.accountId(),
                10L,
                "PENDING_PLAYER_PAYMENT_CUSTODIAL_CREDIT",
                "PENDING_PLAYER_PAYMENT_PREFERENCE_CUSTODIAL",
                player.getUniqueId(),
                null
        )).thenReturn(new BalanceRecord(10L, 0L));

        PlayerPaymentQueueService service = new PlayerPaymentQueueService(
                accountRegistryService,
                fundsRepository,
                pendingPlayerPaymentRepository,
                liveMoneyService,
                new PlayerMoneyLockService(),
                notificationService,
                schedulerService,
                loggers
        );

        var result = service.acceptOnlinePayment(player, 10L, "VAULT2_DEPOSIT:QuickShop-Hikari");

        assertTrue(result.success());
        assertEquals("Funds credited to custodial.", result.message());
        verify(fundsRepository).changeAvailable(
                account.accountId(),
                10L,
                "PENDING_PLAYER_PAYMENT_CUSTODIAL_CREDIT",
                "PENDING_PLAYER_PAYMENT_PREFERENCE_CUSTODIAL",
                player.getUniqueId(),
                null
        );
        verifyNoInteractions(pendingPlayerPaymentRepository);
        verifyNoInteractions(liveMoneyService);
    }

    @Test
    void requestDrainLeavesPaymentQueuedWhenPlayerIsLocked() {
        PlayerMock player = server.addPlayer();
        AccountRegistryService accountRegistryService = mock(AccountRegistryService.class);
        FundsRepository fundsRepository = mock(FundsRepository.class);
        PendingPlayerPaymentRepository pendingPlayerPaymentRepository = mock(PendingPlayerPaymentRepository.class);
        LiveMoneyService liveMoneyService = mock(LiveMoneyService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);
        EconomyLoggers loggers = mock(EconomyLoggers.class);
        PlayerMoneyLockService playerMoneyLockService = new PlayerMoneyLockService();
        PendingPlayerPayment payment = new PendingPlayerPayment(
                UUID.randomUUID(),
                player.getUniqueId(),
                10L,
                PendingPlayerPaymentStatus.PROCESSING,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                com.earthpol.economyPol.economy.model.PendingPlayerPaymentAttemptResult.NONE
        );

        playerMoneyLockService.lock(player.getUniqueId());
        when(schedulerService.runAsync(any(Runnable.class), anyString())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return true;
        });
        when(pendingPlayerPaymentRepository.claimNextRetryablePayment(eq(player.getUniqueId()), anyLong()))
                .thenReturn(Optional.of(payment), Optional.empty());

        PlayerPaymentQueueService service = new PlayerPaymentQueueService(
                accountRegistryService,
                fundsRepository,
                pendingPlayerPaymentRepository,
                liveMoneyService,
                playerMoneyLockService,
                notificationService,
                schedulerService,
                loggers
        );

        service.requestDrain(player.getUniqueId(), "test");

        verify(pendingPlayerPaymentRepository).requeuePayment(
                payment.pendingPaymentId(),
                PendingPlayerPaymentAttemptResult.PLAYER_LOCKED
        );
        verifyNoInteractions(liveMoneyService);
        verifyNoInteractions(notificationService);
    }

    @Test
    void requestDrainCreditsOfflineWalletWhenPlayerIsOffline() {
        UUID playerUuid = UUID.randomUUID();

        AccountRegistryService accountRegistryService = mock(AccountRegistryService.class);
        FundsRepository fundsRepository = mock(FundsRepository.class);
        PendingPlayerPaymentRepository pendingPlayerPaymentRepository = mock(PendingPlayerPaymentRepository.class);
        LiveMoneyService liveMoneyService = mock(LiveMoneyService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);
        EconomyLoggers loggers = mock(EconomyLoggers.class);

        PendingPlayerPayment payment = new PendingPlayerPayment(
                UUID.randomUUID(),
                playerUuid,
                10L,
                PendingPlayerPaymentStatus.PROCESSING,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                com.earthpol.economyPol.economy.model.PendingPlayerPaymentAttemptResult.NONE
        );

        when(schedulerService.runAsync(any(Runnable.class), anyString())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return true;
        });
        when(pendingPlayerPaymentRepository.claimNextRetryablePayment(eq(playerUuid), anyLong()))
                .thenReturn(Optional.of(payment), Optional.empty());
        when(pendingPlayerPaymentRepository.completePaymentToOfflineWallet(payment.pendingPaymentId())).thenReturn(true);

        PlayerPaymentQueueService service = new PlayerPaymentQueueService(
                accountRegistryService,
                fundsRepository,
                pendingPlayerPaymentRepository,
                liveMoneyService,
                new PlayerMoneyLockService(),
                notificationService,
                schedulerService,
                loggers
        );

        service.requestDrain(playerUuid, "test");

        verify(pendingPlayerPaymentRepository).completePaymentToOfflineWallet(payment.pendingPaymentId());
        verify(pendingPlayerPaymentRepository, never()).requeuePayment(any(UUID.class), any());
    }

    @Test
    void requestDrainDeliversLiveAndRoutesRemainderToCustodial() {
        PlayerMock player = server.addPlayer();
        UUID playerUuid = player.getUniqueId();
        AccountRegistryService accountRegistryService = mock(AccountRegistryService.class);
        FundsRepository fundsRepository = mock(FundsRepository.class);
        PendingPlayerPaymentRepository pendingPlayerPaymentRepository = mock(PendingPlayerPaymentRepository.class);
        LiveMoneyService liveMoneyService = mock(LiveMoneyService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);
        EconomyLoggers loggers = mock(EconomyLoggers.class);
        AccountRecord account = new AccountRecord(playerUuid, AccountType.PLAYER, playerUuid, player.getName());
        PendingPlayerPayment payment = new PendingPlayerPayment(
                UUID.randomUUID(),
                playerUuid,
                10L,
                PendingPlayerPaymentStatus.PROCESSING,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                com.earthpol.economyPol.economy.model.PendingPlayerPaymentAttemptResult.NONE
        );
        LiveMoneyService.LiveContainerSnapshot snapshot = new LiveMoneyService.LiveContainerSnapshot(
                player.getInventory().getContents(),
                player.getEnderChest().getContents(),
                player.getInventory().getItemInOffHand()
        );

        when(schedulerService.runAsync(any(Runnable.class), anyString())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return true;
        });
        when(schedulerService.scheduleOnPlayerEntityScheduler(eq(player), any(Runnable.class), any(Runnable.class), anyString()))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(1)).run();
                    return true;
                });
        when(pendingPlayerPaymentRepository.claimNextRetryablePayment(eq(playerUuid), anyLong()))
                .thenReturn(Optional.of(payment), Optional.empty());
        when(accountRegistryService.getIncomingPaymentDeliveryPreference(playerUuid))
                .thenReturn(IncomingPaymentDeliveryPreference.DEFAULT);
        when(accountRegistryService.requirePlayerAccount(player)).thenReturn(account);
        when(liveMoneyService.captureLiveContainerSnapshot(player)).thenReturn(snapshot);
        when(liveMoneyService.deliver(player, 10L, List.of(
                com.earthpol.economyPol.economy.model.MoneyRouteTarget.INVENTORY,
                com.earthpol.economyPol.economy.model.MoneyRouteTarget.ENDER_CHEST,
                com.earthpol.economyPol.economy.model.MoneyRouteTarget.CUSTODIAL_ACCOUNT
        ), true)).thenReturn(new LiveMoneyService.DeliveryResult(6L, 0L, 4L));
        when(pendingPlayerPaymentRepository.completePaymentToCustodial(
                payment.pendingPaymentId(),
                account.accountId(),
                4L,
                "PENDING_PLAYER_PAYMENT_CUSTODIAL_CREDIT",
                "PENDING_PLAYER_PAYMENT_OVERFLOW"
        )).thenReturn(new PendingPlayerPaymentRepository.CompletionResult(10L, new BalanceRecord(4L, 0L)));

        PlayerPaymentQueueService service = new PlayerPaymentQueueService(
                accountRegistryService,
                fundsRepository,
                pendingPlayerPaymentRepository,
                liveMoneyService,
                new PlayerMoneyLockService(),
                notificationService,
                schedulerService,
                loggers
        );

        service.requestDrain(playerUuid, "test");

        verify(pendingPlayerPaymentRepository).completePaymentToCustodial(
                payment.pendingPaymentId(),
                account.accountId(),
                4L,
                "PENDING_PLAYER_PAYMENT_CUSTODIAL_CREDIT",
                "PENDING_PLAYER_PAYMENT_OVERFLOW"
        );
        verify(notificationService).notifyIncomingOverflowToCustodial(player, 4L, 4L);
    }

    @Test
    void requestDrainRequeuesWhenEntitySchedulerTaskIsRetired() {
        PlayerMock player = server.addPlayer();
        UUID playerUuid = player.getUniqueId();
        AccountRegistryService accountRegistryService = mock(AccountRegistryService.class);
        FundsRepository fundsRepository = mock(FundsRepository.class);
        PendingPlayerPaymentRepository pendingPlayerPaymentRepository = mock(PendingPlayerPaymentRepository.class);
        LiveMoneyService liveMoneyService = mock(LiveMoneyService.class);
        NotificationService notificationService = mock(NotificationService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);
        EconomyLoggers loggers = mock(EconomyLoggers.class);
        PendingPlayerPayment payment = new PendingPlayerPayment(
                UUID.randomUUID(),
                playerUuid,
                10L,
                PendingPlayerPaymentStatus.PROCESSING,
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                com.earthpol.economyPol.economy.model.PendingPlayerPaymentAttemptResult.NONE
        );

        when(schedulerService.runAsync(any(Runnable.class), anyString())).thenAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return true;
        });
        when(pendingPlayerPaymentRepository.claimNextRetryablePayment(eq(playerUuid), anyLong()))
                .thenReturn(Optional.of(payment), Optional.empty());
        when(accountRegistryService.getIncomingPaymentDeliveryPreference(playerUuid))
                .thenReturn(IncomingPaymentDeliveryPreference.DEFAULT);
        when(schedulerService.scheduleOnPlayerEntityScheduler(eq(player), any(Runnable.class), any(Runnable.class), anyString()))
                .thenAnswer(invocation -> {
                    ((Runnable) invocation.getArgument(2)).run();
                    return true;
                });

        PlayerPaymentQueueService service = new PlayerPaymentQueueService(
                accountRegistryService,
                fundsRepository,
                pendingPlayerPaymentRepository,
                liveMoneyService,
                new PlayerMoneyLockService(),
                notificationService,
                schedulerService,
                loggers
        );

        service.requestDrain(playerUuid, "test");

        verify(pendingPlayerPaymentRepository).requeuePayment(
                payment.pendingPaymentId(),
                PendingPlayerPaymentAttemptResult.ENTITY_SCHEDULER_UNAVAILABLE
        );
        verifyNoInteractions(liveMoneyService);
    }
}
