package com.earthpol.economyPol.economy.service.player;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.model.IncomingPaymentDeliveryPreference;
import com.earthpol.economyPol.economy.model.MoneyOperationFailureReason;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import com.earthpol.economyPol.economy.model.PendingPlayerPayment;
import com.earthpol.economyPol.economy.model.PendingPlayerPaymentAttemptResult;
import com.earthpol.economyPol.economy.repository.FundsRepository;
import com.earthpol.economyPol.economy.repository.PendingPlayerPaymentRepository;
import com.earthpol.economyPol.economy.service.account.AccountRegistryService;
import com.earthpol.economyPol.economy.service.money.LiveMoneyService;
import com.earthpol.economyPol.economy.service.support.SchedulerService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PlayerPaymentQueueService {

    private static final long RETRY_INITIAL_DELAY_SECONDS = 5L;
    private static final long RETRY_PERIOD_SECONDS = 5L;
    private static final long STALE_PROCESSING_TIMEOUT_MILLIS = 30_000L;
    private static final int MAX_PLAYERS_PER_SWEEP = 100;
    private static final String CUSTODIAL_ENTRY_TYPE = "PENDING_PLAYER_PAYMENT_CUSTODIAL_CREDIT";
    private static final String CUSTODIAL_REASON_OVERFLOW = "PENDING_PLAYER_PAYMENT_OVERFLOW";
    private static final String CUSTODIAL_REASON_DELIVERY_FAILED = "PENDING_PLAYER_PAYMENT_DELIVERY_FAILED";
    private static final String CUSTODIAL_REASON_PREFERENCE = "PENDING_PLAYER_PAYMENT_PREFERENCE_CUSTODIAL";

    private final AccountRegistryService accountRegistryService;
    private final FundsRepository fundsRepository;
    private final PendingPlayerPaymentRepository pendingPlayerPaymentRepository;
    private final LiveMoneyService liveMoneyService;
    private final PlayerMoneyLockService playerMoneyLockService;
    private final NotificationService notificationService;
    private final SchedulerService schedulerService;
    private final EnhancedLogger operationsLog;
    private final EnhancedLogger auditLog;
    private final Set<UUID> inFlightPlayers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean retryLoopStarted = new AtomicBoolean(false);

    public PlayerPaymentQueueService(
            AccountRegistryService accountRegistryService,
            FundsRepository fundsRepository,
            PendingPlayerPaymentRepository pendingPlayerPaymentRepository,
            LiveMoneyService liveMoneyService,
            PlayerMoneyLockService playerMoneyLockService,
            NotificationService notificationService,
            SchedulerService schedulerService,
            EnhancedLogger operationsLog,
            EnhancedLogger auditLog
    ) {
        this.accountRegistryService = accountRegistryService;
        this.fundsRepository = fundsRepository;
        this.pendingPlayerPaymentRepository = pendingPlayerPaymentRepository;
        this.liveMoneyService = liveMoneyService;
        this.playerMoneyLockService = playerMoneyLockService;
        this.notificationService = notificationService;
        this.schedulerService = schedulerService;
        this.operationsLog = operationsLog;
        this.auditLog = auditLog;
    }

    public void startRetryLoop() {
        if (!retryLoopStarted.compareAndSet(false, true)) {
            return;
        }
        boolean scheduled = schedulerService.runAsyncAtFixedRate(
                this::retryDuePlayers,
                RETRY_INITIAL_DELAY_SECONDS,
                RETRY_PERIOD_SECONDS,
                TimeUnit.SECONDS,
                "pending-player-payment-retry"
        );
        if (!scheduled) {
            retryLoopStarted.set(false);
        }
    }

    public MoneyOperationResult acceptOnlinePayment(Player player, long amount, String reason) {
        if (amount < 0L) {
            return MoneyOperationResult.failure(
                    amount,
                    "Cannot deposit a negative amount.",
                    MoneyOperationFailureReason.NEGATIVE_AMOUNT
            );
        }
        if (amount == 0L) {
            return MoneyOperationResult.success(0L, 0L, 0L, "Funds accepted for delivery.");
        }

        IncomingPaymentDeliveryPreference preference =
                accountRegistryService.getIncomingPaymentDeliveryPreference(player.getUniqueId());
        if (preference == IncomingPaymentDeliveryPreference.SKIP_INVENTORY_AND_ENDERCHEST) {
            AccountRecord account = accountRegistryService.requirePlayerAccount(player);
            BalanceRecord updatedBalance = fundsRepository.changeAvailable(
                    account.accountId(),
                    amount,
                    CUSTODIAL_ENTRY_TYPE,
                    CUSTODIAL_REASON_PREFERENCE,
                    player.getUniqueId(),
                    null
            );
            auditLog.info("player-deposit-custodial-preference player=" + player.getUniqueId() +
                    " amount=" + amount + " balance=" +
                    (updatedBalance == null ? 0L : updatedBalance.availableBalance()) + " reason=" + reason);
            return MoneyOperationResult.success(amount, amount, 0L, "Funds credited to custodial.");
        }

        PendingPlayerPayment payment = pendingPlayerPaymentRepository.enqueuePayment(player.getUniqueId(), amount);
        auditLog.info("player-deposit-queued player=" + player.getUniqueId() +
                " payment=" + payment.pendingPaymentId() +
                " amount=" + amount + " reason=" + reason);
        requestDrain(player.getUniqueId(), "online-payment-enqueue");
        return MoneyOperationResult.success(amount, amount, 0L, "Funds accepted for delivery.");
    }

    public void requestDrain(OfflinePlayer player, String trigger) {
        if (player == null) {
            return;
        }
        requestDrain(player.getUniqueId(), trigger);
    }

    public void requestDrain(UUID playerUuid, String trigger) {
        if (playerUuid == null || !inFlightPlayers.add(playerUuid)) {
            return;
        }
        boolean scheduled = schedulerService.runAsync(
                () -> drainPendingPayments(playerUuid, trigger),
                "pending-player-payment-drain:" + trigger
        );
        if (!scheduled) {
            inFlightPlayers.remove(playerUuid);
        }
    }

    public long getPendingBalance(UUID playerUuid) {
        return pendingPlayerPaymentRepository.getPendingBalance(playerUuid);
    }

    private void retryDuePlayers() {
        long staleBeforeMillis = System.currentTimeMillis() - STALE_PROCESSING_TIMEOUT_MILLIS;
        List<UUID> duePlayers = pendingPlayerPaymentRepository.listPlayersWithRetryablePayments(
                MAX_PLAYERS_PER_SWEEP,
                staleBeforeMillis
        );
        for (UUID playerUuid : duePlayers) {
            requestDrain(playerUuid, "periodic-retry");
        }
    }

    private void drainPendingPayments(UUID playerUuid, String trigger) {
        try {
            long staleBeforeMillis = System.currentTimeMillis() - STALE_PROCESSING_TIMEOUT_MILLIS;
            while (true) {
                PendingPlayerPayment payment = pendingPlayerPaymentRepository
                        .claimNextRetryablePayment(playerUuid, staleBeforeMillis)
                        .orElse(null);
                if (payment == null) {
                    return;
                }
                ProcessingDecision decision = processClaimedPayment(payment);
                if (decision == ProcessingDecision.STOP) {
                    return;
                }
            }
        } catch (RuntimeException exception) {
            operationsLog.severe("Pending player payment drain failed for " + playerUuid + " trigger=" + trigger + ".", exception);
        } finally {
            inFlightPlayers.remove(playerUuid);
        }
    }

    private ProcessingDecision processClaimedPayment(PendingPlayerPayment payment) {
        Player player = Bukkit.getPlayer(payment.playerUuid());
        if (player == null) {
            boolean deliveredToOfflineWallet = pendingPlayerPaymentRepository.completePaymentToOfflineWallet(payment.pendingPaymentId());
            if (!deliveredToOfflineWallet) {
                pendingPlayerPaymentRepository.requeuePayment(
                        payment.pendingPaymentId(),
                        PendingPlayerPaymentAttemptResult.OFFLINE_ENDER_WALLET_UNAVAILABLE
                );
                return ProcessingDecision.STOP;
            }
            auditLog.info("pending-payment-delivered-offline-wallet id=" + payment.pendingPaymentId() +
                    " player=" + payment.playerUuid() + " amount=" + payment.paymentAmount());
            return ProcessingDecision.CONTINUE;
        }

        if (playerMoneyLockService.isLocked(payment.playerUuid())) {
            pendingPlayerPaymentRepository.requeuePayment(
                    payment.pendingPaymentId(),
                    PendingPlayerPaymentAttemptResult.PLAYER_LOCKED
            );
            return ProcessingDecision.STOP;
        }

        IncomingPaymentDeliveryPreference preference =
                accountRegistryService.getIncomingPaymentDeliveryPreference(payment.playerUuid());
        if (preference == IncomingPaymentDeliveryPreference.SKIP_INVENTORY_AND_ENDERCHEST) {
            completeToCustodial(payment, player, payment.paymentAmount(), CUSTODIAL_REASON_PREFERENCE);
            return ProcessingDecision.CONTINUE;
        }

        LiveDeliveryAttempt attempt = schedulerService.callOnPlayerEntityScheduler(
                player,
                () -> attemptLiveDeliveryOnPlayerEntityScheduler(player, payment.paymentAmount(), preference.effectiveRoutingOrder()),
                "pending-player-payment-live-delivery"
        ).orElse(null);
        if (attempt == null) {
            pendingPlayerPaymentRepository.requeuePayment(
                    payment.pendingPaymentId(),
                    PendingPlayerPaymentAttemptResult.ENTITY_SCHEDULER_UNAVAILABLE
            );
            return ProcessingDecision.STOP;
        }
        if (attempt.playerLocked()) {
            pendingPlayerPaymentRepository.requeuePayment(
                    payment.pendingPaymentId(),
                    PendingPlayerPaymentAttemptResult.PLAYER_LOCKED
            );
            return ProcessingDecision.STOP;
        }
        if (attempt.deliveryFailed()) {
            completeToCustodial(payment, player, payment.paymentAmount(), CUSTODIAL_REASON_DELIVERY_FAILED);
            return ProcessingDecision.CONTINUE;
        }

        try {
            PendingPlayerPaymentRepository.CompletionResult completion =
                    pendingPlayerPaymentRepository.completePaymentToCustodial(
                            payment.pendingPaymentId(),
                            accountRegistryService.requirePlayerAccount(player).accountId(),
                            attempt.deliveryResult().remainder(),
                            CUSTODIAL_ENTRY_TYPE,
                            CUSTODIAL_REASON_OVERFLOW
                    );
            if (attempt.deliveryResult().remainder() > 0L && completion.updatedCustodialBalance() != null) {
                notificationService.notifyIncomingOverflowToCustodial(
                        player,
                        attempt.deliveryResult().remainder(),
                        completion.updatedCustodialBalance().availableBalance()
                );
            }
            auditLog.info("pending-payment-delivered-live id=" + payment.pendingPaymentId() +
                    " player=" + payment.playerUuid() +
                    " amount=" + payment.paymentAmount() +
                    " inventory=" + attempt.deliveryResult().deliveredToInventory() +
                    " ender=" + attempt.deliveryResult().deliveredToEnder() +
                    " overflow=" + attempt.deliveryResult().remainder());
            return ProcessingDecision.CONTINUE;
        } catch (RuntimeException exception) {
            operationsLog.severe("Failed to finalize pending live payment " + payment.pendingPaymentId() + ".", exception);
            restoreSnapshot(player, attempt.preDeliverySnapshot(), payment.pendingPaymentId());
            pendingPlayerPaymentRepository.requeuePayment(
                    payment.pendingPaymentId(),
                    PendingPlayerPaymentAttemptResult.DELIVERY_FAILED
            );
            return ProcessingDecision.STOP;
        }
    }

    private void completeToCustodial(
            PendingPlayerPayment payment,
            Player player,
            long amountToCustodial,
            String reason
    ) {
        AccountRecord account = accountRegistryService.requirePlayerAccount(player);
        PendingPlayerPaymentRepository.CompletionResult completion =
                pendingPlayerPaymentRepository.completePaymentToCustodial(
                        payment.pendingPaymentId(),
                        account.accountId(),
                        amountToCustodial,
                        CUSTODIAL_ENTRY_TYPE,
                        reason
                );
        if (completion.updatedCustodialBalance() != null && reason.equals(CUSTODIAL_REASON_DELIVERY_FAILED)) {
            notificationService.notifyIncomingOverflowToCustodial(
                    player,
                    amountToCustodial,
                    completion.updatedCustodialBalance().availableBalance()
            );
        }
        auditLog.info("pending-payment-delivered-custodial id=" + payment.pendingPaymentId() +
                " player=" + payment.playerUuid() +
                " amount=" + payment.paymentAmount() +
                " credited=" + amountToCustodial +
                " reason=" + reason);
    }

    private LiveDeliveryAttempt attemptLiveDeliveryOnPlayerEntityScheduler(
            Player player,
            long amount,
            List<MoneyRouteTarget> routingOrder
    ) {
        if (playerMoneyLockService.isLocked(player.getUniqueId())) {
            return LiveDeliveryAttempt.lockedOutcome();
        }
        LiveMoneyService.LiveContainerSnapshot preDeliverySnapshot = liveMoneyService.captureLiveContainerSnapshot(player);
        try {
            LiveMoneyService.DeliveryResult deliveryResult = liveMoneyService.deliver(player, amount, routingOrder);
            return LiveDeliveryAttempt.successOutcome(preDeliverySnapshot, deliveryResult);
        } catch (Exception exception) {
            operationsLog.severe("Failed to deliver pending online payment into live containers for " +
                    player.getUniqueId() + ".", exception);
            try {
                liveMoneyService.restoreLiveContainerSnapshot(player, preDeliverySnapshot);
            } catch (RuntimeException restoreException) {
                operationsLog.severe("Failed to restore live containers after pending payment delivery failure for " +
                        player.getUniqueId() + ".", restoreException);
            }
            return LiveDeliveryAttempt.deliveryFailedOutcome(preDeliverySnapshot);
        }
    }

    private void restoreSnapshot(
            Player player,
            LiveMoneyService.LiveContainerSnapshot preDeliverySnapshot,
            UUID pendingPaymentId
    ) {
        schedulerService.callOnPlayerEntityScheduler(
                player,
                () -> {
                    liveMoneyService.restoreLiveContainerSnapshot(player, preDeliverySnapshot);
                    return Boolean.TRUE;
                },
                "pending-player-payment-rollback"
        ).orElseGet(() -> {
            operationsLog.severe("Failed to restore live snapshot after pending payment rollback for " +
                    player.getUniqueId() + " payment=" + pendingPaymentId + ".");
            return Boolean.FALSE;
        });
    }

    private enum ProcessingDecision {
        CONTINUE,
        STOP
    }

    private record LiveDeliveryAttempt(
            boolean playerLocked,
            boolean deliveryFailed,
            LiveMoneyService.LiveContainerSnapshot preDeliverySnapshot,
            LiveMoneyService.DeliveryResult deliveryResult
    ) {
        private static LiveDeliveryAttempt lockedOutcome() {
            return new LiveDeliveryAttempt(true, false, null, null);
        }

        private static LiveDeliveryAttempt deliveryFailedOutcome(LiveMoneyService.LiveContainerSnapshot preDeliverySnapshot) {
            return new LiveDeliveryAttempt(false, true, preDeliverySnapshot, null);
        }

        private static LiveDeliveryAttempt successOutcome(
                LiveMoneyService.LiveContainerSnapshot preDeliverySnapshot,
                LiveMoneyService.DeliveryResult deliveryResult
        ) {
            return new LiveDeliveryAttempt(false, false, preDeliverySnapshot, deliveryResult);
        }
    }
}
