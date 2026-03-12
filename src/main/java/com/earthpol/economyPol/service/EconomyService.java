package com.earthpol.economyPol.service;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.config.PluginSettings;
import com.earthpol.economyPol.domain.AccountRecord;
import com.earthpol.economyPol.domain.AccountType;
import com.earthpol.economyPol.domain.BalanceRecord;
import com.earthpol.economyPol.domain.EnderWalletSnapshot;
import com.earthpol.economyPol.domain.MoneyOperationResult;
import com.earthpol.economyPol.domain.OfflineEnderWalletState;
import com.earthpol.economyPol.domain.PlayerBalanceView;
import com.earthpol.economyPol.persistence.JdbcEconomyRepository;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class EconomyService {

    private final JdbcEconomyRepository repository;
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
            JdbcEconomyRepository repository,
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
        this.repository = repository;
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

    public ReservationService reservationService() {
        return reservationService;
    }

    public AccountRecord ensurePlayerAccount(OfflinePlayer player) {
        return repository.ensurePlayerAccount(player.getUniqueId(), player.getName(), settings.playerPolicy());
    }

    public AccountRecord ensurePlayerAccount(UUID playerUuid, String playerName) {
        return repository.ensurePlayerAccount(playerUuid, playerName, settings.playerPolicy());
    }

    public AccountRecord ensureSharedAccount(String name, OfflinePlayer owner) {
        return repository.ensureSharedAccount(name, owner == null ? null : owner.getUniqueId());
    }

    public AccountRecord ensureSharedAccount(UUID accountId, String name, UUID ownerUuid) {
        return repository.ensureSharedAccount(accountId, name, ownerUuid);
    }

    public boolean createSharedAccount(UUID accountId, String name, UUID ownerUuid) {
        Optional<AccountRecord> byId = repository.findAccount(accountId);
        if (byId.isPresent() && byId.get().accountType() != AccountType.SHARED) {
            return false;
        }
        Optional<AccountRecord> byName = repository.findSharedAccount(name);
        if (byName.isPresent() && !byName.get().accountId().equals(accountId)) {
            return false;
        }
        repository.ensureSharedAccount(accountId, name, ownerUuid);
        return true;
    }

    public PlayerBalanceView balanceView(OfflinePlayer player) {
        AccountRecord account = ensurePlayerAccount(player);
        BalanceRecord balance = repository.getBalance(account.accountId());
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

    public long getCustodialAvailable(OfflinePlayer player) {
        return repository.getBalance(ensurePlayerAccount(player).accountId()).availableBalance();
    }

    public boolean hasEnough(OfflinePlayer player, long amount) {
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
            Optional<LiveMoneyService.SpendResult> spendResultOptional = schedulerService.callOnPlayerEntityScheduler(
                    onlinePlayer,
                    () -> liveMoneyService.spendFromLiveSources(onlinePlayer, amount, settings.routingOrder()),
                    "withdraw-player-live"
            );
            if (spendResultOptional.isEmpty()) {
                return MoneyOperationResult.failure(amount, "Player money could not be accessed safely.");
            }
            LiveMoneyService.SpendResult spendResult = spendResultOptional.get();
            if (!spendResult.success()) {
                if (LiveMoneyService.NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE.equals(spendResult.message())) {
                    notificationService.notifyNotEnoughRoomForChange(onlinePlayer);
                }
                return MoneyOperationResult.failure(amount, spendResult.message());
            }
            auditLog.info("player-withdraw player=" + player.getUniqueId() + " amount=" + amount +
                    " debited=" + spendResult.debitedAmount() + " change=" + spendResult.changeAmount() +
                    " reason=" + reason);
            return MoneyOperationResult.success(amount, amount, 0L, "Funds withdrawn.");
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
        LiveMoneyService.SpendResult spendResult = liveMoneyService.spendFromLiveSources(player, requested, settings.routingOrder());
        if (!spendResult.success()) {
            if (requested <= 0L) {
                return MoneyOperationResult.failure(requested, "No live money was available to deposit.");
            }
            return MoneyOperationResult.failure(requested, spendResult.message());
        }
        if (requested <= 0L) {
            return MoneyOperationResult.failure(requested, "No live money was available to deposit.");
        }
        repository.changeAvailable(account.accountId(), requested, "SELF_DEPOSIT", "SELF_DEPOSIT", player.getUniqueId(), null);
        auditLog.info("self-deposit player=" + player.getUniqueId() + " amount=" + requested +
                " debited=" + spendResult.debitedAmount() + " change=" + spendResult.changeAmount());
        return MoneyOperationResult.success(requested, requested, 0L, "Funds deposited.");
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
        BalanceRecord balance = repository.getBalance(account.accountId());
        if (balance.availableBalance() < amount) {
            return MoneyOperationResult.failure(amount, "Insufficient custodial funds.");
        }
        if (amount == 0L) {
            return MoneyOperationResult.success(0L, 0L, 0L, "Withdraw processed.");
        }

        LiveMoneyService.LiveContainerSnapshot liveSnapshot = liveMoneyService.captureLiveContainerSnapshot(player);
        try {
            repository.reserveAvailable(account.accountId(), amount, "SELF_WITHDRAW_PENDING");
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
            updatedBalance = repository.settleReservedWithdrawal(
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
            repository.releaseReserved(accountId, reservedAmount, "SELF_WITHDRAW_ROLLBACK");
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

    public BalanceRecord creditCustodial(UUID playerUuid, String playerName, long amount, String reason) {
        AccountRecord account = repository.ensurePlayerAccount(playerUuid, playerName, settings.playerPolicy());
        return repository.changeAvailable(account.accountId(), amount, "CUSTODIAL_CREDIT", reason, playerUuid, null);
    }

    public Optional<AccountRecord> findAccount(UUID accountId) {
        return repository.findAccount(accountId);
    }

    public Optional<AccountRecord> findSharedAccount(String bankName) {
        return repository.findSharedAccount(bankName);
    }

    public Optional<String> getAccountName(UUID accountId) {
        return repository.findAccount(accountId).map(AccountRecord::accountName);
    }

    public MoneyOperationResult bankDeposit(String bankName, OfflinePlayer owner, long amount, String reason) {
        AccountRecord account = repository.findSharedAccount(bankName).orElseGet(() -> ensureSharedAccount(bankName, owner));
        repository.changeAvailable(account.accountId(), amount, "BANK_DEPOSIT", reason, owner == null ? null : owner.getUniqueId(), null);
        return MoneyOperationResult.success(amount, amount, 0L, "Bank deposit completed.");
    }

    public MoneyOperationResult bankWithdraw(String bankName, long amount, String reason) {
        Optional<AccountRecord> account = repository.findSharedAccount(bankName);
        if (account.isEmpty()) {
            return MoneyOperationResult.failure(amount, "Bank account does not exist.");
        }
        BalanceRecord balance = repository.getBalance(account.get().accountId());
        if (balance.availableBalance() < amount) {
            return MoneyOperationResult.failure(amount, "Insufficient bank funds.");
        }
        repository.changeAvailable(account.get().accountId(), -amount, "BANK_WITHDRAW", reason, null, null);
        return MoneyOperationResult.success(amount, amount, 0L, "Bank withdrawal completed.");
    }

    public long bankBalance(String bankName) {
        return repository.findSharedAccount(bankName)
                .map(accountRecord -> repository.getBalance(accountRecord.accountId()).availableBalance())
                .orElse(0L);
    }

    public List<String> listBanks() {
        return repository.listSharedAccountNames();
    }

    public Map<UUID, String> accountNameMap() {
        return repository.listAccountNames();
    }

    public Optional<AccountRecord> findAccountByName(String name) {
        return repository.findAccountByName(name);
    }

    public boolean isPlayerLocked(UUID playerUuid) {
        return playerMoneyLockService.isLocked(playerUuid);
    }

    public boolean renameAccount(UUID accountId, String name) {
        Optional<AccountRecord> existing = repository.findAccount(accountId);
        if (existing.isEmpty()) {
            return false;
        }
        Optional<AccountRecord> collision = repository.findAccountByName(name);
        if (collision.isPresent() && !collision.get().accountId().equals(accountId)) {
            return false;
        }
        return repository.renameAccount(accountId, name);
    }

    public boolean deleteSharedAccount(UUID accountId) {
        Optional<AccountRecord> existing = repository.findAccount(accountId);
        if (existing.isEmpty() || existing.get().accountType() != AccountType.SHARED) {
            return false;
        }
        return repository.deleteSharedAccount(accountId);
    }

    public long getBalance(UUID accountId) {
        Optional<AccountRecord> account = repository.findAccount(accountId);
        if (account.isEmpty()) {
            return 0L;
        }
        if (account.get().accountType() == AccountType.PLAYER) {
            return getBalance(Bukkit.getOfflinePlayer(accountId));
        }
        return repository.getBalance(accountId).availableBalance();
    }

    public boolean hasEnough(UUID accountId, long amount) {
        return getBalance(accountId) >= amount;
    }

    public MoneyOperationResult withdrawAccount(UUID accountId, long amount, String reason) {
        Optional<AccountRecord> account = repository.findAccount(accountId);
        if (account.isEmpty()) {
            return MoneyOperationResult.failure(amount, "Account does not exist.");
        }
        if (account.get().accountType() == AccountType.PLAYER) {
            return withdrawPlayer(Bukkit.getOfflinePlayer(accountId), amount, reason);
        }
        BalanceRecord balance = repository.getBalance(accountId);
        if (balance.availableBalance() < amount) {
            return MoneyOperationResult.failure(amount, "Insufficient funds.");
        }
        repository.changeAvailable(accountId, -amount, "SHARED_WITHDRAW", reason, null, null);
        auditLog.info("shared-withdraw account=" + accountId + " amount=" + amount + " reason=" + reason);
        return MoneyOperationResult.success(amount, amount, 0L, "Funds withdrawn.");
    }

    public MoneyOperationResult depositAccount(UUID accountId, long amount, String reason) {
        Optional<AccountRecord> account = repository.findAccount(accountId);
        if (account.isEmpty()) {
            return MoneyOperationResult.failure(amount, "Account does not exist.");
        }
        if (account.get().accountType() == AccountType.PLAYER) {
            return depositPlayer(Bukkit.getOfflinePlayer(accountId), amount, reason);
        }
        repository.changeAvailable(accountId, amount, "SHARED_DEPOSIT", reason, null, null);
        auditLog.info("shared-deposit account=" + accountId + " amount=" + amount + " reason=" + reason);
        return MoneyOperationResult.success(amount, amount, 0L, "Funds deposited.");
    }

    public boolean isAccountOwner(UUID accountId, UUID subjectUuid) {
        Optional<AccountRecord> account = repository.findAccount(accountId);
        if (account.isEmpty() || subjectUuid == null) {
            return false;
        }
        if (account.get().accountType() == AccountType.PLAYER) {
            return accountId.equals(subjectUuid);
        }
        return subjectUuid.equals(account.get().ownerUuid());
    }

    public boolean setSharedAccountOwner(UUID accountId, UUID ownerUuid) {
        Optional<AccountRecord> account = repository.findSharedAccount(accountId);
        if (account.isEmpty()) {
            return false;
        }
        return repository.updateSharedAccountOwner(accountId, ownerUuid);
    }

    public boolean isAccountMember(UUID accountId, UUID subjectUuid) {
        if (isAccountOwner(accountId, subjectUuid)) {
            return true;
        }
        return repository.findAccountMemberRole(accountId, subjectUuid).isPresent();
    }

    public boolean addSharedAccountMember(UUID accountId, UUID memberUuid) {
        Optional<AccountRecord> account = repository.findSharedAccount(accountId);
        if (account.isEmpty() || memberUuid == null || isAccountOwner(accountId, memberUuid)) {
            return false;
        }
        repository.upsertAccountMember(accountId, memberUuid, "MEMBER");
        auditLog.info("shared-member-add account=" + accountId + " member=" + memberUuid);
        return true;
    }

    public boolean removeSharedAccountMember(UUID accountId, UUID memberUuid) {
        Optional<AccountRecord> account = repository.findSharedAccount(accountId);
        if (account.isEmpty() || memberUuid == null || isAccountOwner(accountId, memberUuid)) {
            return false;
        }
        boolean removed = repository.removeAccountMember(accountId, memberUuid);
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
