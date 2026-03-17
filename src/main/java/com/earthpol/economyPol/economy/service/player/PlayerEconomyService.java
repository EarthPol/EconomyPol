package com.earthpol.economyPol.economy.service.player;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.model.EnderWalletSnapshot;
import com.earthpol.economyPol.economy.model.IncomingPaymentDeliveryPreference;
import com.earthpol.economyPol.economy.model.MoneyOperationFailureReason;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import com.earthpol.economyPol.economy.model.OfflineEnderWalletState;
import com.earthpol.economyPol.economy.model.PlayerBalanceView;
import com.earthpol.economyPol.economy.repository.FundsRepository;
import com.earthpol.economyPol.economy.service.account.AccountRegistryService;
import com.earthpol.economyPol.economy.service.money.LiveMoneyService;
import com.earthpol.economyPol.economy.service.support.SchedulerService;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns player-specific economy behavior, including live-money interaction,
 * custodial overflow, and offline managed ender-wallet behavior.
 */
public final class PlayerEconomyService {

    private static final List<MoneyRouteTarget> INVENTORY_ONLY_ROUTING = List.of(MoneyRouteTarget.INVENTORY);

    private final AccountRegistryService accountRegistryService;
    private final FundsRepository fundsRepository;
    private final LiveMoneyService liveMoneyService;
    private final EnderWalletService enderWalletService;
    private final PlayerPaymentQueueService playerPaymentQueueService;
    private final PlayerMoneyLockService playerMoneyLockService;
    private final NotificationService notificationService;
    private final SchedulerService schedulerService;
    private final PluginSettings settings;
    private final EnhancedLogger operationsLog;
    private final EnhancedLogger auditLog;

    public PlayerEconomyService(
            AccountRegistryService accountRegistryService,
            FundsRepository fundsRepository,
            LiveMoneyService liveMoneyService,
            EnderWalletService enderWalletService,
            PlayerPaymentQueueService playerPaymentQueueService,
            PlayerMoneyLockService playerMoneyLockService,
            NotificationService notificationService,
            SchedulerService schedulerService,
            PluginSettings settings,
            EnhancedLogger operationsLog,
            EnhancedLogger auditLog
    ) {
        this.accountRegistryService = accountRegistryService;
        this.fundsRepository = fundsRepository;
        this.liveMoneyService = liveMoneyService;
        this.enderWalletService = enderWalletService;
        this.playerPaymentQueueService = playerPaymentQueueService;
        this.playerMoneyLockService = playerMoneyLockService;
        this.notificationService = notificationService;
        this.schedulerService = schedulerService;
        this.settings = settings;
        this.operationsLog = operationsLog;
        this.auditLog = auditLog;
    }

    public PlayerBalanceView balanceView(OfflinePlayer player) {
        AccountRecord account = accountRegistryService.requirePlayerAccount(player);
        BalanceRecord balance = fundsRepository.getBalance(account.accountId());
        boolean locked = playerMoneyLockService.isLocked(player.getUniqueId());
        long inventoryMoney = 0L;
        long enderChestMoney = 0L;
        long frozen = 0L;
        if (player.isOnline() && player.getPlayer() != null) {
            Player onlinePlayer = player.getPlayer();
            LiveMoneyService.LiveMoneyBreakdown breakdown = liveMoneyService.scanPlayerMoneyBreakdown(onlinePlayer);
            inventoryMoney = breakdown.inventory();
            enderChestMoney = breakdown.enderChest();
        } else {
            Optional<EnderWalletSnapshot> snapshot = enderWalletService.findSnapshot(player.getUniqueId());
            if (snapshot.isPresent() && snapshot.get().state() == OfflineEnderWalletState.FROZEN) {
                frozen = snapshot.get().baseUnits();
            }
        }
        return new PlayerBalanceView(
                balance.availableBalance(),
                balance.reservedBalance(),
                inventoryMoney,
                enderChestMoney,
                frozen,
                locked
        );
    }

    public long getBalance(OfflinePlayer player) {
        return balanceView(player).spendable();
    }

    public long scanOnlinePlayerMoney(Player player) {
        return liveMoneyService.scanPlayerMoney(player);
    }

    public long getCustodialAvailable(OfflinePlayer player) {
        return fundsRepository.getBalance(accountRegistryService.requirePlayerAccount(player).accountId()).availableBalance();
    }

    public boolean hasEnough(OfflinePlayer player, long amount) {
        accountRegistryService.requirePlayerAccount(player);
        // Towny can pre-check has()/hasEnough() and then continue even if the later withdraw fails.
        // In FAIL mode we work around that by simulating live spendability here so "enough" means
        // the player can actually complete the spend with the currently available change space.
        if (settings.changeOverflowPolicy() == PluginSettings.ChangeOverflowPolicy.FAIL && player.isOnline() && player.getPlayer() != null) {
            Player onlinePlayer = player.getPlayer();
            if (playerMoneyLockService.isLocked(player.getUniqueId())) {
                return false;
            }
            LiveMoneyService.SpendabilityResult spendability = liveMoneyService.canSpendFromLiveSources(
                    onlinePlayer,
                    amount,
                    incomingPaymentRoutingOrder(player.getUniqueId()),
                    settings.changeOverflowPolicy()
            );
            if (!spendability.success()
                    && LiveMoneyService.NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE.equals(spendability.message())) {
                notificationService.notifyNotEnoughRoomForChange(onlinePlayer);
            }
            return spendability.success();
        }
        return getBalance(player) >= amount;
    }

    // withdrawPlayer is the generic spend path used by Vault and account withdrawals.
    // For player accounts it only spends already-physical money: live inventory when online,
    // or the frozen offline ender-wallet snapshot when offline. It never auto-spends custodial.
    public MoneyOperationResult withdrawPlayer(OfflinePlayer player, long amount, String reason) {
        if (amount < 0L) {
            return MoneyOperationResult.failure(
                    amount,
                    "Cannot withdraw a negative amount.",
                    MoneyOperationFailureReason.NEGATIVE_AMOUNT
            );
        }
        if (accountRegistryService.findPlayerAccount(player).isEmpty()) {
            return MoneyOperationResult.failure(
                    amount,
                    "Player account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        if (player.isOnline() && player.getPlayer() != null) {
            if (playerMoneyLockService.isLocked(player.getUniqueId())) {
                return MoneyOperationResult.failure(
                        amount,
                        "Player money is locked during wallet sync.",
                        MoneyOperationFailureReason.PLAYER_MONEY_LOCKED
                );
            }
            Player onlinePlayer = player.getPlayer();
            return withdrawOnlinePlayerOnPlayerEntityScheduler(onlinePlayer, amount, reason);
        }

        MoneyOperationResult walletDebit = enderWalletService.debitOffline(player.getUniqueId(), amount);
        if (walletDebit.processedAmount() < amount) {
            if (walletDebit.processedAmount() > 0L) {
                enderWalletService.creditOffline(player.getUniqueId(), walletDebit.processedAmount());
            }
            return MoneyOperationResult.failure(
                    amount,
                    "Insufficient funds.",
                    MoneyOperationFailureReason.INSUFFICIENT_FUNDS
            );
        }
        auditLog.info("offline-withdraw player=" + player.getUniqueId() + " amount=" + amount +
                " ender=" + walletDebit.processedAmount() + " reason=" + reason);
        return MoneyOperationResult.success(amount, amount, 0L, "Funds withdrawn.");
    }

    public MoneyOperationResult depositPlayer(OfflinePlayer player, long amount, String reason) {
        if (amount < 0L) {
            return MoneyOperationResult.failure(
                    amount,
                    "Cannot deposit a negative amount.",
                    MoneyOperationFailureReason.NEGATIVE_AMOUNT
            );
        }
        if (accountRegistryService.findPlayerAccount(player).isEmpty()) {
            return MoneyOperationResult.failure(
                    amount,
                    "Player account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        if (player.isOnline() && player.getPlayer() != null) {
            return playerPaymentQueueService.acceptOnlinePayment(player.getPlayer(), amount, reason);
        }

        return depositOffline(player, amount, reason);
    }

    public MoneyOperationResult depositSelf(Player player, long amount) {
        if (accountRegistryService.findPlayerAccount(player).isEmpty()) {
            return MoneyOperationResult.failure(
                    amount,
                    "Player account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        return schedulerService.callOnPlayerEntityScheduler(
                player,
                () -> depositSelfOnPlayerEntityScheduler(player, amount),
                "self-deposit"
        ).orElse(MoneyOperationResult.failure(
                amount,
                "Player money could not be accessed safely.",
                MoneyOperationFailureReason.PLAYER_MONEY_ACCESS_UNAVAILABLE
        ));
    }

    public MoneyOperationResult withdrawCustodialAsPhysicalMoney(
            Player player,
            long amount,
            List<MoneyRouteTarget> routingOrder
    ) {
        if (accountRegistryService.findPlayerAccount(player).isEmpty()) {
            return MoneyOperationResult.failure(
                    amount,
                    "Player account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        List<MoneyRouteTarget> effectiveRoutingOrder = sanitizeExplicitWithdrawRoutingOrder(amount, routingOrder);
        if (effectiveRoutingOrder.isEmpty()) {
            return MoneyOperationResult.failure(
                    amount,
                    "At least one routing target must be provided.",
                    MoneyOperationFailureReason.INVALID_ROUTING_ORDER
            );
        }
        return schedulerService.callOnPlayerEntityScheduler(
                player,
                () -> withdrawCustodialAsPhysicalMoneyOnPlayerEntityScheduler(
                        player,
                        amount,
                        liveMoneyService.captureLiveContainerSnapshot(player),
                        effectiveRoutingOrder
                ),
                "withdraw-custodial-as-physical-money"
        ).orElse(MoneyOperationResult.failure(
                amount,
                "Player money could not be accessed safely.",
                MoneyOperationFailureReason.PLAYER_MONEY_ACCESS_UNAVAILABLE
        ));
    }

    public MoneyOperationResult withdrawMaxCustodialToInventory(Player player) {
        if (accountRegistryService.findPlayerAccount(player).isEmpty()) {
            return MoneyOperationResult.failure(
                    0L,
                    "Player account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        long requestedAmount = Math.max(0L, getCustodialAvailable(player));
        return schedulerService.callOnPlayerEntityScheduler(
                player,
                () -> withdrawMaxCustodialToInventoryOnPlayerEntityScheduler(player),
                "withdraw-max-custodial-to-inventory"
        ).orElse(MoneyOperationResult.failure(
                requestedAmount,
                "Player money could not be accessed safely.",
                MoneyOperationFailureReason.PLAYER_MONEY_ACCESS_UNAVAILABLE
        ));
    }

    public BalanceRecord creditCustodial(UUID playerUuid, String playerName, long amount, String reason) {
        AccountRecord account = accountRegistryService.requirePlayerAccount(playerUuid);
        return fundsRepository.changeAvailable(account.accountId(), amount, "CUSTODIAL_CREDIT", reason, playerUuid, null);
    }

    public boolean isPlayerLocked(UUID playerUuid) {
        return playerMoneyLockService.isLocked(playerUuid);
    }

    public long getPendingIncomingPaymentBalance(UUID playerUuid) {
        return playerPaymentQueueService.getPendingBalance(playerUuid);
    }

    private MoneyOperationResult depositSelfOnPlayerEntityScheduler(Player player, long amount) {
        AccountRecord account = accountRegistryService.requirePlayerAccount(player);
        long available = liveMoneyService.scanPlayerMoney(player);
        long requested = amount <= 0L ? available : amount;
        LiveMoneyService.LiveContainerSnapshot liveSnapshot = liveMoneyService.captureLiveContainerSnapshot(player);
        LiveMoneyService.SpendResult spendResult = liveMoneyService.spendFromLiveSources(
                player,
                requested,
                incomingPaymentRoutingOrder(player.getUniqueId()),
                settings.changeOverflowPolicy()
        );
        if (!spendResult.success()) {
            if (requested <= 0L) {
                return MoneyOperationResult.failure(
                        requested,
                        "No live money was available to deposit.",
                        MoneyOperationFailureReason.NO_LIVE_MONEY_AVAILABLE
                );
            }
            return MoneyOperationResult.failure(
                    requested,
                    spendResult.message(),
                    mapSpendFailureReason(spendResult.message())
            );
        }
        if (requested <= 0L) {
            return MoneyOperationResult.failure(
                    requested,
                    "No live money was available to deposit.",
                    MoneyOperationFailureReason.NO_LIVE_MONEY_AVAILABLE
            );
        }
        long totalCredited = requested + spendResult.changeRoutedToCustodial();
        BalanceRecord updatedBalance;
        try {
            updatedBalance = fundsRepository.changeAvailable(
                    account.accountId(),
                    totalCredited,
                    "SELF_DEPOSIT",
                    "SELF_DEPOSIT",
                    player.getUniqueId(),
                    null
            );
        } catch (RuntimeException exception) {
            operationsLog.severe("Failed to deposit live player money into custodial for " + player.getUniqueId() + ".", exception);
            liveMoneyService.restoreLiveContainerSnapshot(player, liveSnapshot);
            return MoneyOperationResult.failure(
                    requested,
                    "Physical deposit failed while finalizing custodial balance.",
                    MoneyOperationFailureReason.BALANCE_FINALIZATION_FAILED
            );
        }
        if (spendResult.changeRoutedToCustodial() > 0L) {
            notificationService.notifyChangeRoutedToCustodial(
                    player,
                    spendResult.changeRoutedToCustodial(),
                    updatedBalance.availableBalance()
            );
        }
        auditLog.info("self-deposit player=" + player.getUniqueId() + " amount=" + requested +
                " debited=" + spendResult.debitedAmount() + " change=" + spendResult.changeAmount() +
                " change_routed_to_custodial=" + spendResult.changeRoutedToCustodial() +
                " total_credited=" + totalCredited);
        return MoneyOperationResult.success(requested, totalCredited, 0L, "Funds deposited.");
    }

    private MoneyOperationResult withdrawMaxCustodialToInventoryOnPlayerEntityScheduler(Player player) {
        long maxWithdrawable = getMaxWithdrawableToInventory(player);
        if (maxWithdrawable <= 0L) {
            long availableBalance = getCustodialAvailable(player);
            if (availableBalance <= 0L) {
                return MoneyOperationResult.failure(
                        0L,
                        "Insufficient custodial funds.",
                        MoneyOperationFailureReason.INSUFFICIENT_FUNDS
                );
            }
            return MoneyOperationResult.failure(
                    availableBalance,
                    "No room in your inventory to withdraw physical money.",
                    MoneyOperationFailureReason.NO_INVENTORY_SPACE
            );
        }

        LiveMoneyService.LiveContainerSnapshot liveSnapshot = liveMoneyService.captureLiveContainerSnapshot(player);

        return withdrawCustodialAsPhysicalMoneyOnPlayerEntityScheduler(
                player,
                maxWithdrawable,
                liveSnapshot,
                INVENTORY_ONLY_ROUTING
        );
    }

    private MoneyOperationResult withdrawCustodialAsPhysicalMoneyOnPlayerEntityScheduler(
            Player player,
            long amount,
            LiveMoneyService.LiveContainerSnapshot liveSnapshot,
            List<MoneyRouteTarget> routingOrder
    ) {
        if (amount < 0L) {
            return MoneyOperationResult.failure(
                    amount,
                    "Cannot withdraw a negative amount.",
                    MoneyOperationFailureReason.NEGATIVE_AMOUNT
            );
        }
        AccountRecord account = accountRegistryService.requirePlayerAccount(player);
        BalanceRecord balance = fundsRepository.getBalance(account.accountId());
        if (balance.availableBalance() < amount) {
            return MoneyOperationResult.failure(
                    amount,
                    "Insufficient custodial funds.",
                    MoneyOperationFailureReason.INSUFFICIENT_FUNDS
            );
        }
        if (amount == 0L) {
            return MoneyOperationResult.success(0L, 0L, 0L, "Withdraw processed.");
        }

        try {
            fundsRepository.reserveAvailable(account.accountId(), amount, "SELF_WITHDRAW_PENDING");
        } catch (RuntimeException exception) {
            return MoneyOperationResult.failure(
                    amount,
                    "Insufficient custodial funds.",
                    MoneyOperationFailureReason.INSUFFICIENT_FUNDS
            );
        }

        LiveMoneyService.DeliveryResult deliveryResult;
        try {
            deliveryResult = liveMoneyService.deliver(player, amount, routingOrder);
        } catch (Exception exception) {
            rollbackCustodialWithdrawal(player, liveSnapshot, account.accountId(), amount, exception);
            return MoneyOperationResult.failure(
                    amount,
                    "Physical withdrawal failed while delivering money.",
                    MoneyOperationFailureReason.DELIVERY_FAILED
            );
        }

        long delivered = amount - deliveryResult.remainder();
        BalanceRecord updatedBalance;
        try {
            updatedBalance = fundsRepository.settleReservedWithdrawal(
                    account.accountId(),
                    delivered,
                    deliveryResult.remainder(),
                    "SELF_WITHDRAW_CAPTURE",
                    "SELF_WITHDRAW_REMAINDER",
                    player.getUniqueId()
            );
        } catch (RuntimeException exception) {
            rollbackCustodialWithdrawal(player, liveSnapshot, account.accountId(), amount, exception);
            return MoneyOperationResult.failure(
                    amount,
                    "Physical withdrawal failed while finalizing balances.",
                    MoneyOperationFailureReason.BALANCE_FINALIZATION_FAILED
            );
        }

        if (deliveryResult.remainder() > 0L) {
            try {
                notificationService.notifyWithdrawalRetainedInCustodial(
                        player,
                        deliveryResult.remainder(),
                        updatedBalance.availableBalance()
                );
            } catch (RuntimeException exception) {
                operationsLog.severe("Failed to send custodial remainder notification to " + player.getUniqueId() + ".", exception);
            }
        }
        auditLog.info("self-withdraw player=" + player.getUniqueId() + " requested=" + amount +
                " delivered=" + delivered + " retained=" + deliveryResult.remainder());
        return MoneyOperationResult.success(amount, delivered, deliveryResult.remainder(), "Withdraw processed.");
    }

    private void rollbackCustodialWithdrawal(
            Player player,
            LiveMoneyService.LiveContainerSnapshot liveSnapshot,
            UUID accountId,
            long reservedAmount,
            Exception exception
    ) {
        operationsLog.severe("Failed to convert custodial funds into physical money for " + player.getUniqueId() + ".", exception);
        try {
            liveMoneyService.restoreLiveContainerSnapshot(player, liveSnapshot);
        } catch (RuntimeException restoreException) {
            operationsLog.severe("Failed to restore live money containers after custodial withdraw rollback for " +
                    player.getUniqueId() + ".", restoreException);
        }
        try {
            fundsRepository.releaseReserved(accountId, reservedAmount, "SELF_WITHDRAW_ROLLBACK");
        } catch (RuntimeException releaseException) {
            operationsLog.severe("Failed to release reserved custodial funds after rollback for " +
                    player.getUniqueId() + ".", releaseException);
        }
    }

    private MoneyOperationResult depositOffline(OfflinePlayer player, long amount, String reason) {
        MoneyOperationResult walletCredit = enderWalletService.creditOffline(player.getUniqueId(), amount);
        if (walletCredit.success()) {
            return walletCredit;
        }
        BalanceRecord updatedBalance = creditCustodial(player.getUniqueId(), player.getName(), amount, reason);
        notificationService.queueOfflineCreditToCustodial(
                player.getUniqueId(),
                amount,
                updatedBalance.availableBalance()
        );
        auditLog.info("offline-deposit-custodial player=" + player.getUniqueId() + " amount=" + amount + " reason=" + reason);
        return MoneyOperationResult.success(amount, amount, 0L, "Funds credited to custodial.");
    }

    private MoneyOperationResult withdrawOnlinePlayerOnPlayerEntityScheduler(Player player, long amount, String reason) {
        LiveMoneyService.LiveContainerSnapshot liveSnapshot = liveMoneyService.captureLiveContainerSnapshot(player);
        LiveMoneyService.SpendResult spendResult = liveMoneyService.spendFromLiveSources(
                player,
                amount,
                incomingPaymentRoutingOrder(player.getUniqueId()),
                settings.changeOverflowPolicy()
        );
        if (!spendResult.success()) {
            if (LiveMoneyService.NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE.equals(spendResult.message())) {
                notificationService.notifyNotEnoughRoomForChange(player);
            }
            return MoneyOperationResult.failure(
                    amount,
                    spendResult.message(),
                    mapSpendFailureReason(spendResult.message())
            );
        }

        if (spendResult.changeRoutedToCustodial() > 0L) {
            try {
                BalanceRecord updatedBalance = creditCustodial(
                        player.getUniqueId(),
                        player.getName(),
                        spendResult.changeRoutedToCustodial(),
                        "LIVE_CHANGE_OVERFLOW"
                );
                notificationService.notifyChangeRoutedToCustodial(
                        player,
                        spendResult.changeRoutedToCustodial(),
                        updatedBalance.availableBalance()
                );
            } catch (RuntimeException exception) {
                operationsLog.severe("Failed to route returned change into custodial for " + player.getUniqueId() + ".", exception);
                liveMoneyService.restoreLiveContainerSnapshot(player, liveSnapshot);
                return MoneyOperationResult.failure(
                        amount,
                        "Failed to route change to custodial.",
                        MoneyOperationFailureReason.CHANGE_ROUTING_FAILED
                );
            }
        }

        auditLog.info("player-withdraw player=" + player.getUniqueId() + " amount=" + amount +
                " debited=" + spendResult.debitedAmount() + " change=" + spendResult.changeAmount() +
                " change_routed_to_custodial=" + spendResult.changeRoutedToCustodial() +
                " reason=" + reason);
        return MoneyOperationResult.success(amount, amount, 0L, "Funds withdrawn.");
    }

    private List<MoneyRouteTarget> incomingPaymentRoutingOrder(UUID playerUuid) {
        IncomingPaymentDeliveryPreference preference =
                accountRegistryService.getIncomingPaymentDeliveryPreference(playerUuid);
        return preference.effectiveRoutingOrder();
    }

    private List<MoneyRouteTarget> sanitizeExplicitWithdrawRoutingOrder(long amount, List<MoneyRouteTarget> routingOrder) {
        if (routingOrder == null || routingOrder.isEmpty()) {
            return List.of();
        }

        List<MoneyRouteTarget> effectiveRoutingOrder = List.copyOf(routingOrder);
        EnumSet<MoneyRouteTarget> seen = EnumSet.noneOf(MoneyRouteTarget.class);
        for (MoneyRouteTarget target : effectiveRoutingOrder) {
            if (!seen.add(target)) {
                operationsLog.warn("Rejecting explicit custodial withdraw with duplicate routing target. amount="
                        + amount + " target=" + target);
                return List.of();
            }
        }
        return effectiveRoutingOrder;
    }

    public long getMaxWithdrawableToInventory(Player player) {
        AccountRecord account = accountRegistryService.requirePlayerAccount(player);
        BalanceRecord balance = fundsRepository.getBalance(account.accountId());
        if (balance.availableBalance() <= 0L) {
            return 0L;
        }
        return liveMoneyService.maxDeliverableToInventory(
                liveMoneyService.captureLiveContainerSnapshot(player),
                balance.availableBalance()
        );
    }

    private MoneyOperationFailureReason mapSpendFailureReason(String message) {
        if ("Cannot spend a negative amount.".equals(message)) {
            return MoneyOperationFailureReason.NEGATIVE_AMOUNT;
        }
        if ("Insufficient funds.".equals(message)) {
            return MoneyOperationFailureReason.INSUFFICIENT_FUNDS;
        }
        if (LiveMoneyService.NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE.equals(message)) {
            return MoneyOperationFailureReason.NOT_ENOUGH_ROOM_FOR_CHANGE;
        }
        return MoneyOperationFailureReason.UNKNOWN;
    }
}
