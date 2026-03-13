package com.earthpol.economyPol.economy.service.player;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.model.EnderWalletSnapshot;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.model.OfflineEnderWalletState;
import com.earthpol.economyPol.economy.model.PlayerBalanceView;
import com.earthpol.economyPol.economy.repository.FundsRepository;
import com.earthpol.economyPol.economy.service.account.AccountRegistryService;
import com.earthpol.economyPol.economy.service.money.LiveMoneyService;
import com.earthpol.economyPol.economy.service.support.SchedulerService;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns player-specific economy behavior, including live-money interaction,
 * custodial overflow, and offline managed ender-wallet behavior.
 */
public final class PlayerEconomyService {

    private final AccountRegistryService accountRegistryService;
    private final FundsRepository fundsRepository;
    private final LiveMoneyService liveMoneyService;
    private final EnderWalletService enderWalletService;
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
        this.playerMoneyLockService = playerMoneyLockService;
        this.notificationService = notificationService;
        this.schedulerService = schedulerService;
        this.settings = settings;
        this.operationsLog = operationsLog;
        this.auditLog = auditLog;
    }

    public PlayerBalanceView balanceView(OfflinePlayer player) {
        AccountRecord account = accountRegistryService.ensurePlayerAccount(player);
        BalanceRecord balance = fundsRepository.getBalance(account.accountId());
        boolean locked = playerMoneyLockService.isLocked(player.getUniqueId());
        long liveMoney = 0L;
        long frozen = 0L;
        if (player.isOnline() && player.getPlayer() != null) {
            Player onlinePlayer = player.getPlayer();
            liveMoney = schedulerService.callOnPlayerEntityScheduler(
                    onlinePlayer,
                    () -> liveMoneyService.scanPlayerMoney(onlinePlayer),
                    "balance-view-live-scan"
            ).orElse(0L);
        } else {
            Optional<EnderWalletSnapshot> snapshot = enderWalletService.findSnapshot(player.getUniqueId());
            if (snapshot.isPresent() && snapshot.get().state() == OfflineEnderWalletState.FROZEN) {
                frozen = snapshot.get().baseUnits();
            }
        }
        return new PlayerBalanceView(balance.availableBalance(), balance.reservedBalance(), liveMoney, frozen, locked);
    }

    public long getBalance(OfflinePlayer player) {
        return balanceView(player).spendable();
    }

    public long scanOnlinePlayerMoney(Player player) {
        return schedulerService.callOnPlayerEntityScheduler(
                player,
                () -> liveMoneyService.scanPlayerMoney(player),
                "balancetop-live-scan"
        ).orElse(0L);
    }

    public long getCustodialAvailable(OfflinePlayer player) {
        return fundsRepository.getBalance(accountRegistryService.ensurePlayerAccount(player).accountId()).availableBalance();
    }

    public boolean hasEnough(OfflinePlayer player, long amount) {
        // Towny can pre-check has()/hasEnough() and then continue even if the later withdraw fails.
        // In FAIL mode we work around that by simulating live spendability here so "enough" means
        // the player can actually complete the spend with the currently available change space.
        if (settings.changeOverflowPolicy() == PluginSettings.ChangeOverflowPolicy.FAIL && player.isOnline() && player.getPlayer() != null) {
            Player onlinePlayer = player.getPlayer();
            if (playerMoneyLockService.isLocked(player.getUniqueId())) {
                return false;
            }
            Optional<LiveMoneyService.SpendabilityResult> spendabilityOptional = schedulerService.callOnPlayerEntityScheduler(
                    onlinePlayer,
                    () -> liveMoneyService.canSpendFromLiveSources(
                            onlinePlayer,
                            amount,
                            settings.routingOrder(),
                            settings.changeOverflowPolicy()
                    ),
                    "has-enough-live-spendability"
            );
            if (spendabilityOptional.isEmpty()) {
                return false;
            }
            LiveMoneyService.SpendabilityResult spendability = spendabilityOptional.get();
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
            return MoneyOperationResult.failure(amount, "Cannot withdraw a negative amount.");
        }
        accountRegistryService.ensurePlayerAccount(player);
        if (player.isOnline() && player.getPlayer() != null) {
            if (playerMoneyLockService.isLocked(player.getUniqueId())) {
                return MoneyOperationResult.failure(amount, "Player money is locked during wallet sync.");
            }
            Player onlinePlayer = player.getPlayer();
            Optional<MoneyOperationResult> withdrawResultOptional = schedulerService.callOnPlayerEntityScheduler(
                    onlinePlayer,
                    () -> withdrawOnlinePlayerOnPlayerEntityScheduler(onlinePlayer, amount, reason),
                    "withdraw-player-live"
            );
            if (withdrawResultOptional.isEmpty()) {
                return MoneyOperationResult.failure(amount, "Player money could not be accessed safely.");
            }
            return withdrawResultOptional.get();
        }

        MoneyOperationResult walletDebit = enderWalletService.debitOffline(player.getUniqueId(), amount);
        if (walletDebit.processedAmount() < amount) {
            if (walletDebit.processedAmount() > 0L) {
                enderWalletService.creditOffline(player.getUniqueId(), walletDebit.processedAmount());
            }
            return MoneyOperationResult.failure(amount, "Insufficient funds.");
        }
        auditLog.info("offline-withdraw player=" + player.getUniqueId() + " amount=" + amount +
                " ender=" + walletDebit.processedAmount() + " reason=" + reason);
        return MoneyOperationResult.success(amount, amount, 0L, "Funds withdrawn.");
    }

    public MoneyOperationResult depositPlayer(OfflinePlayer player, long amount, String reason) {
        if (amount < 0L) {
            return MoneyOperationResult.failure(amount, "Cannot deposit a negative amount.");
        }
        accountRegistryService.ensurePlayerAccount(player);
        if (player.isOnline() && player.getPlayer() != null) {
            Player onlinePlayer = player.getPlayer();
            Optional<LiveMoneyService.DeliveryResult> deliveryResultOptional = schedulerService.callOnPlayerEntityScheduler(
                    onlinePlayer,
                    () -> liveMoneyService.deliver(onlinePlayer, amount, settings.routingOrder()),
                    "deposit-player-live"
            );
            if (deliveryResultOptional.isEmpty()) {
                if (!player.isOnline() || player.getPlayer() == null) {
                    return depositOffline(player, amount, reason);
                }
                return MoneyOperationResult.failure(amount, "Player money could not be delivered safely.");
            }
            LiveMoneyService.DeliveryResult deliveryResult = deliveryResultOptional.get();
            if (deliveryResult.remainder() > 0L) {
                BalanceRecord updatedBalance = creditCustodial(
                        player.getUniqueId(),
                        player.getName(),
                        deliveryResult.remainder(),
                        "ONLINE_ROUTE_OVERFLOW"
                );
                notificationService.notifyIncomingOverflowToCustodial(
                        onlinePlayer,
                        deliveryResult.remainder(),
                        updatedBalance.availableBalance()
                );
            }
            auditLog.info("player-deposit player=" + player.getUniqueId() + " amount=" + amount +
                    " inventory=" + deliveryResult.deliveredToInventory() + " ender=" + deliveryResult.deliveredToEnder() +
                    " overflow=" + deliveryResult.remainder() + " reason=" + reason);
            return MoneyOperationResult.success(amount, amount, 0L, "Funds delivered.");
        }

        return depositOffline(player, amount, reason);
    }

    public MoneyOperationResult depositSelf(Player player, long amount) {
        return schedulerService.callOnPlayerEntityScheduler(
                player,
                () -> depositSelfOnPlayerEntityScheduler(player, amount),
                "self-deposit"
        ).orElse(MoneyOperationResult.failure(amount, "Player money could not be accessed safely."));
    }

    public MoneyOperationResult withdrawCustodialAsPhysicalMoney(Player player, long amount) {
        return schedulerService.callOnPlayerEntityScheduler(
                player,
                () -> withdrawCustodialAsPhysicalMoneyOnPlayerEntityScheduler(player, amount),
                "withdraw-custodial-as-physical-money"
        ).orElse(MoneyOperationResult.failure(amount, "Player money could not be accessed safely."));
    }

    public MoneyOperationResult withdrawMaxCustodialToInventory(Player player) {
        long requestedAmount = Math.max(0L, getCustodialAvailable(player));
        return schedulerService.callOnPlayerEntityScheduler(
                player,
                () -> withdrawMaxCustodialToInventoryOnPlayerEntityScheduler(player),
                "withdraw-max-custodial-to-inventory"
        ).orElse(MoneyOperationResult.failure(requestedAmount, "Player money could not be accessed safely."));
    }

    public BalanceRecord creditCustodial(UUID playerUuid, String playerName, long amount, String reason) {
        AccountRecord account = accountRegistryService.ensurePlayerAccount(playerUuid, playerName);
        return fundsRepository.changeAvailable(account.accountId(), amount, "CUSTODIAL_CREDIT", reason, playerUuid, null);
    }

    public boolean isPlayerLocked(UUID playerUuid) {
        return playerMoneyLockService.isLocked(playerUuid);
    }

    private MoneyOperationResult depositSelfOnPlayerEntityScheduler(Player player, long amount) {
        AccountRecord account = accountRegistryService.ensurePlayerAccount(player);
        if (!account.playerPolicy().allowSelfDeposit()) {
            return MoneyOperationResult.failure(amount, "Self-deposit is disabled.");
        }
        long available = liveMoneyService.scanPlayerMoney(player);
        long requested = amount <= 0L ? available : amount;
        LiveMoneyService.LiveContainerSnapshot liveSnapshot = liveMoneyService.captureLiveContainerSnapshot(player);
        LiveMoneyService.SpendResult spendResult = liveMoneyService.spendFromLiveSources(
                player,
                requested,
                settings.routingOrder(),
                settings.changeOverflowPolicy()
        );
        if (!spendResult.success()) {
            if (requested <= 0L) {
                return MoneyOperationResult.failure(requested, "No live money was available to deposit.");
            }
            return MoneyOperationResult.failure(requested, spendResult.message());
        }
        if (requested <= 0L) {
            return MoneyOperationResult.failure(requested, "No live money was available to deposit.");
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
            return MoneyOperationResult.failure(requested, "Physical deposit failed while finalizing custodial balance.");
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

    // withdrawCustodialAsPhysicalMoney is the explicit conversion path from custodial storage
    // into physical money items delivered by the configured routing order. In other words, it
    // converts money stored in the "overflow" account into real money in the player's inventory/ender chest.
    // This is not automatic so custodial overflow does not become silently spendable.
    private MoneyOperationResult withdrawCustodialAsPhysicalMoneyOnPlayerEntityScheduler(Player player, long amount) {
        LiveMoneyService.LiveContainerSnapshot liveSnapshot = liveMoneyService.captureLiveContainerSnapshot(player);
        return withdrawCustodialAsPhysicalMoneyOnPlayerEntityScheduler(player, amount, liveSnapshot, settings.routingOrder());
    }

    private MoneyOperationResult withdrawMaxCustodialToInventoryOnPlayerEntityScheduler(Player player) {
        AccountRecord account = accountRegistryService.ensurePlayerAccount(player);
        if (!account.playerPolicy().allowSelfWithdraw()) {
            return MoneyOperationResult.failure(0L, "Self-withdraw is disabled.");
        }

        BalanceRecord balance = fundsRepository.getBalance(account.accountId());
        if (balance.availableBalance() <= 0L) {
            return MoneyOperationResult.failure(0L, "Insufficient custodial funds.");
        }

        LiveMoneyService.LiveContainerSnapshot liveSnapshot = liveMoneyService.captureLiveContainerSnapshot(player);
        long maxWithdrawable = liveMoneyService.maxDeliverableToInventory(liveSnapshot, balance.availableBalance());
        if (maxWithdrawable <= 0L) {
            return MoneyOperationResult.failure(balance.availableBalance(), "No room in your inventory to withdraw physical money.");
        }

        return withdrawCustodialAsPhysicalMoneyOnPlayerEntityScheduler(
                player,
                maxWithdrawable,
                liveSnapshot,
                List.of(com.earthpol.economyPol.economy.model.MoneyRouteTarget.INVENTORY)
        );
    }

    private MoneyOperationResult withdrawCustodialAsPhysicalMoneyOnPlayerEntityScheduler(
            Player player,
            long amount,
            LiveMoneyService.LiveContainerSnapshot liveSnapshot,
            List<com.earthpol.economyPol.economy.model.MoneyRouteTarget> routingOrder
    ) {
        if (amount < 0L) {
            return MoneyOperationResult.failure(amount, "Cannot withdraw a negative amount.");
        }
        AccountRecord account = accountRegistryService.ensurePlayerAccount(player);
        if (!account.playerPolicy().allowSelfWithdraw()) {
            return MoneyOperationResult.failure(amount, "Self-withdraw is disabled.");
        }
        BalanceRecord balance = fundsRepository.getBalance(account.accountId());
        if (balance.availableBalance() < amount) {
            return MoneyOperationResult.failure(amount, "Insufficient custodial funds.");
        }
        if (amount == 0L) {
            return MoneyOperationResult.success(0L, 0L, 0L, "Withdraw processed.");
        }

        try {
            fundsRepository.reserveAvailable(account.accountId(), amount, "SELF_WITHDRAW_PENDING");
        } catch (RuntimeException exception) {
            return MoneyOperationResult.failure(amount, "Insufficient custodial funds.");
        }

        LiveMoneyService.DeliveryResult deliveryResult;
        try {
            deliveryResult = liveMoneyService.deliver(player, amount, routingOrder);
        } catch (Exception exception) {
            rollbackCustodialWithdrawal(player, liveSnapshot, account.accountId(), amount, exception);
            return MoneyOperationResult.failure(amount, "Physical withdrawal failed while delivering money.");
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
            return MoneyOperationResult.failure(amount, "Physical withdrawal failed while finalizing balances.");
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
                settings.routingOrder(),
                settings.changeOverflowPolicy()
        );
        if (!spendResult.success()) {
            if (LiveMoneyService.NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE.equals(spendResult.message())) {
                notificationService.notifyNotEnoughRoomForChange(player);
            }
            return MoneyOperationResult.failure(amount, spendResult.message());
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
                return MoneyOperationResult.failure(amount, "Failed to route change to custodial.");
            }
        }

        auditLog.info("player-withdraw player=" + player.getUniqueId() + " amount=" + amount +
                " debited=" + spendResult.debitedAmount() + " change=" + spendResult.changeAmount() +
                " change_routed_to_custodial=" + spendResult.changeRoutedToCustodial() +
                " reason=" + reason);
        return MoneyOperationResult.success(amount, amount, 0L, "Funds withdrawn.");
    }
}

