package com.earthpol.economyPol.service;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.config.PluginSettings;
import com.earthpol.economyPol.model.AccountRecord;
import com.earthpol.economyPol.model.AccountType;
import com.earthpol.economyPol.model.BalanceRecord;
import com.earthpol.economyPol.model.EnderWalletSnapshot;
import com.earthpol.economyPol.model.MoneyOperationResult;
import com.earthpol.economyPol.model.OfflineEnderWalletState;
import com.earthpol.economyPol.model.PlayerBalanceView;
import com.earthpol.economyPol.repository.AccountRepository;
import com.earthpol.economyPol.repository.FundsRepository;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class EconomyService {

    private final AccountRepository accountRepository;
    private final FundsRepository fundsRepository;
    private final DenominationService denominationService;
    private final LiveMoneyService liveMoneyService;
    private final EnderWalletService enderWalletService;
    private final PlayerMoneyLockService playerMoneyLockService;
    private final ReservationService reservationService;
    private final NotificationService notificationService;
    private final SchedulerService schedulerService;
    private final PluginSettings settings;
    private final EnhancedLogger operationsLog;
    private final EnhancedLogger auditLog;

    public EconomyService(
            AccountRepository accountRepository,
            FundsRepository fundsRepository,
            DenominationService denominationService,
            LiveMoneyService liveMoneyService,
            EnderWalletService enderWalletService,
            PlayerMoneyLockService playerMoneyLockService,
            ReservationService reservationService,
            NotificationService notificationService,
            SchedulerService schedulerService,
            PluginSettings settings,
            EnhancedLogger operationsLog,
            EnhancedLogger auditLog
    ) {
        this.accountRepository = accountRepository;
        this.fundsRepository = fundsRepository;
        this.denominationService = denominationService;
        this.liveMoneyService = liveMoneyService;
        this.enderWalletService = enderWalletService;
        this.playerMoneyLockService = playerMoneyLockService;
        this.reservationService = reservationService;
        this.notificationService = notificationService;
        this.schedulerService = schedulerService;
        this.settings = settings;
        this.operationsLog = operationsLog;
        this.auditLog = auditLog;
    }

    public LiveMoneyService liveMoneyService() {
        return liveMoneyService;
    }

    public SchedulerService schedulerService() {
        return schedulerService;
    }

    public ReservationService reservationService() {
        return reservationService;
    }

    public AccountRecord ensurePlayerAccount(OfflinePlayer player) {
        return accountRepository.ensurePlayerAccount(player.getUniqueId(), player.getName(), settings.playerPolicy());
    }

    public AccountRecord ensurePlayerAccount(UUID playerUuid, String playerName) {
        return accountRepository.ensurePlayerAccount(playerUuid, playerName, settings.playerPolicy());
    }

    public AccountRecord ensureSharedAccount(String name, OfflinePlayer owner) {
        return accountRepository.ensureSharedAccount(name, owner == null ? null : owner.getUniqueId());
    }

    public AccountRecord ensureSharedAccount(UUID accountId, String name, UUID ownerUuid) {
        return accountRepository.ensureSharedAccount(accountId, name, ownerUuid);
    }

    public boolean createSharedAccount(UUID accountId, String name, UUID ownerUuid) {
        Optional<AccountRecord> byId = accountRepository.findAccount(accountId);
        if (byId.isPresent() && byId.get().accountType() != AccountType.SHARED) {
            return false;
        }
        Optional<AccountRecord> byName = accountRepository.findSharedAccount(name);
        if (byName.isPresent() && !byName.get().accountId().equals(accountId)) {
            return false;
        }
        accountRepository.ensureSharedAccount(accountId, name, ownerUuid);
        return true;
    }

    public PlayerBalanceView balanceView(OfflinePlayer player) {
        AccountRecord account = ensurePlayerAccount(player);
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
        return fundsRepository.getBalance(ensurePlayerAccount(player).accountId()).availableBalance();
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
            if (!spendability.success() &&
                    LiveMoneyService.NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE.equals(spendability.message())) {
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
        ensurePlayerAccount(player);
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
        ensurePlayerAccount(player);
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

    private MoneyOperationResult depositSelfOnPlayerEntityScheduler(Player player, long amount) {
        AccountRecord account = ensurePlayerAccount(player);
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
    public MoneyOperationResult withdrawCustodialAsPhysicalMoney(Player player, long amount) {
        return schedulerService.callOnPlayerEntityScheduler(
                player,
                () -> withdrawCustodialAsPhysicalMoneyOnPlayerEntityScheduler(player, amount),
                "withdraw-custodial-as-physical-money"
        ).orElse(MoneyOperationResult.failure(amount, "Player money could not be accessed safely."));
    }

    private MoneyOperationResult withdrawCustodialAsPhysicalMoneyOnPlayerEntityScheduler(Player player, long amount) {
        if (amount < 0L) {
            return MoneyOperationResult.failure(amount, "Cannot withdraw a negative amount.");
        }
        AccountRecord account = ensurePlayerAccount(player);
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

        LiveMoneyService.LiveContainerSnapshot liveSnapshot = liveMoneyService.captureLiveContainerSnapshot(player);
        try {
            fundsRepository.reserveAvailable(account.accountId(), amount, "SELF_WITHDRAW_PENDING");
        } catch (RuntimeException exception) {
            return MoneyOperationResult.failure(amount, "Insufficient custodial funds.");
        }

        LiveMoneyService.DeliveryResult deliveryResult;
        try {
            deliveryResult = liveMoneyService.deliver(player, amount, settings.routingOrder());
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

    public BalanceRecord creditCustodial(UUID playerUuid, String playerName, long amount, String reason) {
        AccountRecord account = accountRepository.ensurePlayerAccount(playerUuid, playerName, settings.playerPolicy());
        return fundsRepository.changeAvailable(account.accountId(), amount, "CUSTODIAL_CREDIT", reason, playerUuid, null);
    }

    public Optional<AccountRecord> findAccount(UUID accountId) {
        return accountRepository.findAccount(accountId);
    }

    public Optional<AccountRecord> findSharedAccount(String bankName) {
        return accountRepository.findSharedAccount(bankName);
    }

    public Optional<String> getAccountName(UUID accountId) {
        return accountRepository.findAccount(accountId).map(AccountRecord::accountName);
    }

    public MoneyOperationResult bankDeposit(String bankName, OfflinePlayer owner, long amount, String reason) {
        AccountRecord account = accountRepository.findSharedAccount(bankName).orElseGet(() -> ensureSharedAccount(bankName, owner));
        fundsRepository.changeAvailable(account.accountId(), amount, "BANK_DEPOSIT", reason, owner == null ? null : owner.getUniqueId(), null);
        return MoneyOperationResult.success(amount, amount, 0L, "Bank deposit completed.");
    }

    public MoneyOperationResult bankWithdraw(String bankName, long amount, String reason) {
        Optional<AccountRecord> account = accountRepository.findSharedAccount(bankName);
        if (account.isEmpty()) {
            return MoneyOperationResult.failure(amount, "Bank account does not exist.");
        }
        BalanceRecord balance = fundsRepository.getBalance(account.get().accountId());
        if (balance.availableBalance() < amount) {
            return MoneyOperationResult.failure(amount, "Insufficient bank funds.");
        }
        fundsRepository.changeAvailable(account.get().accountId(), -amount, "BANK_WITHDRAW", reason, null, null);
        return MoneyOperationResult.success(amount, amount, 0L, "Bank withdrawal completed.");
    }

    public long bankBalance(String bankName) {
        return accountRepository.findSharedAccount(bankName)
                .map(accountRecord -> fundsRepository.getBalance(accountRecord.accountId()).availableBalance())
                .orElse(0L);
    }

    public List<String> listBanks() {
        return accountRepository.listSharedAccountNames();
    }

    public List<AccountRecord> listSharedAccounts() {
        return accountRepository.listSharedAccounts();
    }

    public Map<UUID, String> accountNameMap() {
        return accountRepository.listAccountNames();
    }

    public Optional<AccountRecord> findAccountByName(String name) {
        return accountRepository.findAccountByName(name);
    }

    public boolean isPlayerLocked(UUID playerUuid) {
        return playerMoneyLockService.isLocked(playerUuid);
    }

    public boolean renameAccount(UUID accountId, String name) {
        Optional<AccountRecord> existing = accountRepository.findAccount(accountId);
        if (existing.isEmpty()) {
            return false;
        }
        Optional<AccountRecord> collision = accountRepository.findAccountByName(name);
        if (collision.isPresent() && !collision.get().accountId().equals(accountId)) {
            return false;
        }
        return accountRepository.renameAccount(accountId, name);
    }

    public boolean deleteSharedAccount(UUID accountId) {
        Optional<AccountRecord> existing = accountRepository.findAccount(accountId);
        if (existing.isEmpty() || existing.get().accountType() != AccountType.SHARED) {
            return false;
        }
        return accountRepository.deleteSharedAccount(accountId);
    }

    public long getBalance(UUID accountId) {
        Optional<AccountRecord> account = accountRepository.findAccount(accountId);
        if (account.isEmpty()) {
            return 0L;
        }
        if (account.get().accountType() == AccountType.PLAYER) {
            return getBalance(Bukkit.getOfflinePlayer(accountId));
        }
        return fundsRepository.getBalance(accountId).availableBalance();
    }

    public boolean hasEnough(UUID accountId, long amount) {
        Optional<AccountRecord> account = accountRepository.findAccount(accountId);
        if (account.isEmpty()) {
            return false;
        }
        if (account.get().accountType() == AccountType.PLAYER) {
            return hasEnough(Bukkit.getOfflinePlayer(accountId), amount);
        }
        return fundsRepository.getBalance(accountId).availableBalance() >= amount;
    }

    public MoneyOperationResult withdrawAccount(UUID accountId, long amount, String reason) {
        Optional<AccountRecord> account = accountRepository.findAccount(accountId);
        if (account.isEmpty()) {
            return MoneyOperationResult.failure(amount, "Account does not exist.");
        }
        if (account.get().accountType() == AccountType.PLAYER) {
            return withdrawPlayer(Bukkit.getOfflinePlayer(accountId), amount, reason);
        }
        BalanceRecord balance = fundsRepository.getBalance(accountId);
        if (balance.availableBalance() < amount) {
            return MoneyOperationResult.failure(amount, "Insufficient funds.");
        }
        fundsRepository.changeAvailable(accountId, -amount, "SHARED_WITHDRAW", reason, null, null);
        auditLog.info("shared-withdraw account=" + accountId + " amount=" + amount + " reason=" + reason);
        return MoneyOperationResult.success(amount, amount, 0L, "Funds withdrawn.");
    }

    public MoneyOperationResult depositAccount(UUID accountId, long amount, String reason) {
        Optional<AccountRecord> account = accountRepository.findAccount(accountId);
        if (account.isEmpty()) {
            return MoneyOperationResult.failure(amount, "Account does not exist.");
        }
        if (account.get().accountType() == AccountType.PLAYER) {
            return depositPlayer(Bukkit.getOfflinePlayer(accountId), amount, reason);
        }
        fundsRepository.changeAvailable(accountId, amount, "SHARED_DEPOSIT", reason, null, null);
        auditLog.info("shared-deposit account=" + accountId + " amount=" + amount + " reason=" + reason);
        return MoneyOperationResult.success(amount, amount, 0L, "Funds deposited.");
    }

    public boolean isAccountOwner(UUID accountId, UUID subjectUuid) {
        Optional<AccountRecord> account = accountRepository.findAccount(accountId);
        if (account.isEmpty() || subjectUuid == null) {
            return false;
        }
        if (account.get().accountType() == AccountType.PLAYER) {
            return accountId.equals(subjectUuid);
        }
        return subjectUuid.equals(account.get().ownerUuid());
    }

    public boolean setSharedAccountOwner(UUID accountId, UUID ownerUuid) {
        Optional<AccountRecord> account = accountRepository.findSharedAccount(accountId);
        if (account.isEmpty()) {
            return false;
        }
        return accountRepository.updateSharedAccountOwner(accountId, ownerUuid);
    }

    public boolean isAccountMember(UUID accountId, UUID subjectUuid) {
        if (isAccountOwner(accountId, subjectUuid)) {
            return true;
        }
        return accountRepository.findAccountMemberRole(accountId, subjectUuid).isPresent();
    }

    public boolean addSharedAccountMember(UUID accountId, UUID memberUuid) {
        Optional<AccountRecord> account = accountRepository.findSharedAccount(accountId);
        if (account.isEmpty() || memberUuid == null || isAccountOwner(accountId, memberUuid)) {
            return false;
        }
        accountRepository.upsertAccountMember(accountId, memberUuid, "MEMBER");
        auditLog.info("shared-member-add account=" + accountId + " member=" + memberUuid);
        return true;
    }

    public boolean removeSharedAccountMember(UUID accountId, UUID memberUuid) {
        Optional<AccountRecord> account = accountRepository.findSharedAccount(accountId);
        if (account.isEmpty() || memberUuid == null || isAccountOwner(accountId, memberUuid)) {
            return false;
        }
        boolean removed = accountRepository.removeAccountMember(accountId, memberUuid);
        if (removed) {
            auditLog.info("shared-member-remove account=" + accountId + " member=" + memberUuid);
        }
        return removed;
    }

    public DenominationService denominationService() {
        return denominationService;
    }

    public EnhancedLogger operationsLog() {
        return operationsLog;
    }
}
