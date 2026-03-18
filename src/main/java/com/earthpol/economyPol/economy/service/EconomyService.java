package com.earthpol.economyPol.economy.service;

import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.AccountType;
import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.model.IncomingPaymentDeliveryPreference;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.model.MoneyOperationFailureReason;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import com.earthpol.economyPol.economy.model.PlayerBalanceView;
import com.earthpol.economyPol.economy.repository.AccountRepository;
import com.earthpol.economyPol.economy.repository.FundsRepository;
import com.earthpol.economyPol.economy.repository.PendingPlayerPaymentRepository;
import com.earthpol.economyPol.economy.repository.PlayerRepository;
import com.earthpol.economyPol.economy.service.account.AccountRegistryService;
import com.earthpol.economyPol.economy.service.account.SharedAccountService;
import com.earthpol.economyPol.economy.service.money.LiveMoneyService;
import com.earthpol.economyPol.economy.service.player.EnderWalletService;
import com.earthpol.economyPol.economy.service.player.NotificationService;
import com.earthpol.economyPol.economy.service.player.PlayerEconomyService;
import com.earthpol.economyPol.economy.service.player.PlayerMoneyLockService;
import com.earthpol.economyPol.economy.service.player.PlayerPaymentQueueService;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import com.earthpol.economyPol.economy.service.support.ReservationService;
import com.earthpol.economyPol.economy.service.support.SchedulerService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Public economy facade used by adapters, commands, and the plugin bootstrap.
 * Focused services own the actual player/shared-account behavior underneath.
 */
public final class EconomyService {

    private final DenominationService denominationService;
    private final LiveMoneyService liveMoneyService;
    private final ReservationService reservationService;
    private final SchedulerService schedulerService;
    private final EconomyLoggers loggers;

    private final AccountRegistryService accountRegistryService;
    private final PlayerEconomyService playerEconomyService;
    private final SharedAccountService sharedAccountService;
    private final PlayerPaymentQueueService playerPaymentQueueService;

    public EconomyService(
            AccountRepository accountRepository,
            PlayerRepository playerRepository,
            FundsRepository fundsRepository,
            PendingPlayerPaymentRepository pendingPlayerPaymentRepository,
            DenominationService denominationService,
            LiveMoneyService liveMoneyService,
            EnderWalletService enderWalletService,
            PlayerMoneyLockService playerMoneyLockService,
            ReservationService reservationService,
            NotificationService notificationService,
            SchedulerService schedulerService,
            PluginSettings settings,
            EconomyLoggers loggers
    ) {
        this.denominationService = denominationService;
        this.liveMoneyService = liveMoneyService;
        this.reservationService = reservationService;
        this.schedulerService = schedulerService;
        this.loggers = loggers;

        this.accountRegistryService = new AccountRegistryService(
                accountRepository,
                playerRepository,
                settings
        );
        this.playerPaymentQueueService = new PlayerPaymentQueueService(
                accountRegistryService,
                fundsRepository,
                pendingPlayerPaymentRepository,
                liveMoneyService,
                playerMoneyLockService,
                notificationService,
                schedulerService,
                loggers
        );
        this.playerEconomyService = new PlayerEconomyService(
                accountRegistryService,
                fundsRepository,
                liveMoneyService,
                enderWalletService,
                playerPaymentQueueService,
                playerMoneyLockService,
                notificationService,
                schedulerService,
                settings,
                loggers
        );
        this.sharedAccountService = new SharedAccountService(
                accountRegistryService,
                accountRepository,
                fundsRepository,
                loggers
        );
    }

    public LiveMoneyService liveMoneyService() {
        return liveMoneyService;
    }

    public SchedulerService schedulerService() {
        return schedulerService;
    }

    public void startPendingPaymentQueue() {
        playerPaymentQueueService.startRetryLoop();
    }

    public void requestPendingPaymentDrain(OfflinePlayer player, String trigger) {
        playerPaymentQueueService.requestDrain(player, trigger);
    }

    public void requestPendingPaymentDrain(UUID playerUuid, String trigger) {
        playerPaymentQueueService.requestDrain(playerUuid, trigger);
    }

    public ReservationService reservationService() {
        return reservationService;
    }

    public AccountRecord ensurePlayerAccount(OfflinePlayer player) {
        return accountRegistryService.ensurePlayerAccount(player);
    }

    public AccountRecord ensurePlayerAccount(UUID playerUuid, String playerName) {
        return accountRegistryService.ensurePlayerAccount(playerUuid, playerName);
    }

    public Optional<AccountRecord> findPlayerAccount(UUID playerUuid) {
        return accountRegistryService.findPlayerAccount(playerUuid);
    }

    public void registerPlayer(OfflinePlayer player) {
        accountRegistryService.registerPlayer(player);
    }

    public void registerPlayer(UUID playerUuid, String playerName) {
        accountRegistryService.registerPlayer(playerUuid, playerName);
    }

    public void syncPlayerIdentity(OfflinePlayer player) {
        accountRegistryService.syncPlayerIdentity(player);
    }

    public IncomingPaymentDeliveryPreference getIncomingPaymentDeliveryPreference(OfflinePlayer player) {
        accountRegistryService.requirePlayerAccount(player);
        return accountRegistryService.getIncomingPaymentDeliveryPreference(player.getUniqueId());
    }

    public IncomingPaymentDeliveryPreference setIncomingPaymentDeliveryPreference(
            OfflinePlayer player,
            IncomingPaymentDeliveryPreference preference
    ) {
        accountRegistryService.requirePlayerAccount(player);
        return accountRegistryService.setIncomingPaymentDeliveryPreference(
                player.getUniqueId(),
                player.getName(),
                preference
        );
    }

    public IncomingPaymentDeliveryPreference setIncomingPaymentDeliveryPreference(
            UUID playerUuid,
            String playerName,
            IncomingPaymentDeliveryPreference preference
    ) {
        accountRegistryService.requirePlayerAccount(playerUuid);
        return accountRegistryService.setIncomingPaymentDeliveryPreference(
                playerUuid,
                playerName,
                preference
        );
    }

    public AccountRecord ensureSharedAccount(String name, OfflinePlayer owner) {
        return accountRegistryService.ensureSharedAccount(name, owner);
    }

    public AccountRecord ensureSharedAccount(UUID accountId, String name, UUID ownerUuid) {
        return accountRegistryService.ensureSharedAccount(accountId, name, ownerUuid);
    }

    public boolean createSharedAccount(UUID accountId, String name, UUID ownerUuid) {
        return accountRegistryService.createSharedAccount(accountId, name, ownerUuid);
    }

    public PlayerBalanceView balanceView(OfflinePlayer player) {
        accountRegistryService.requirePlayerAccount(player);
        return playerEconomyService.balanceView(player);
    }

    public long getBalance(OfflinePlayer player) {
        accountRegistryService.requirePlayerAccount(player);
        return playerEconomyService.getBalance(player);
    }

    public long getPlayerSpendableBalance(OfflinePlayer player) {
        accountRegistryService.requirePlayerAccount(player);
        return playerEconomyService.getBalance(player);
    }

    public long scanOnlinePlayerMoney(Player player) {
        return playerEconomyService.scanOnlinePlayerMoney(player);
    }

    public long getCustodialAvailable(OfflinePlayer player) {
        accountRegistryService.requirePlayerAccount(player);
        return playerEconomyService.getCustodialAvailable(player);
    }

    public boolean hasEnough(OfflinePlayer player, long amount) {
        accountRegistryService.requirePlayerAccount(player);
        return playerEconomyService.hasEnough(player, amount);
    }

    public boolean playerHasEnough(OfflinePlayer player, long amount) {
        accountRegistryService.requirePlayerAccount(player);
        return playerEconomyService.hasEnough(player, amount);
    }

    public MoneyOperationResult withdrawPlayer(OfflinePlayer player, long amount, String reason) {
        if (accountRegistryService.findPlayerAccount(player).isEmpty()) {
            return MoneyOperationResult.failure(
                    amount,
                    "Player account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        return playerEconomyService.withdrawPlayer(player, amount, reason);
    }

    public MoneyOperationResult withdrawFromPlayerAccount(OfflinePlayer player, long amount, String reason) {
        if (accountRegistryService.findPlayerAccount(player).isEmpty()) {
            return MoneyOperationResult.failure(
                    amount,
                    "Player account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        return playerEconomyService.withdrawPlayer(player, amount, reason);
    }

    public MoneyOperationResult depositPlayer(OfflinePlayer player, long amount, String reason) {
        if (accountRegistryService.findPlayerAccount(player).isEmpty()) {
            return MoneyOperationResult.failure(
                    amount,
                    "Player account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        return playerEconomyService.depositPlayer(player, amount, reason);
    }

    public MoneyOperationResult depositToPlayerAccount(OfflinePlayer player, long amount, String reason) {
        if (accountRegistryService.findPlayerAccount(player).isEmpty()) {
            return MoneyOperationResult.failure(
                    amount,
                    "Player account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        return playerEconomyService.depositPlayer(player, amount, reason);
    }

    public MoneyOperationResult depositSelf(Player player, long amount) {
        return playerEconomyService.depositSelf(player, amount);
    }

    public MoneyOperationResult depositPhysicalMoneyToCustodial(Player player, long amount) {
        if (accountRegistryService.findPlayerAccount(player).isEmpty()) {
            return MoneyOperationResult.failure(
                    amount,
                    "Player account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        return playerEconomyService.depositSelf(player, amount);
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
        return playerEconomyService.withdrawCustodialAsPhysicalMoney(player, amount, routingOrder);
    }

    public MoneyOperationResult withdrawMaxCustodialToInventory(Player player) {
        if (accountRegistryService.findPlayerAccount(player).isEmpty()) {
            return MoneyOperationResult.failure(
                    0L,
                    "Player account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        return playerEconomyService.withdrawMaxCustodialToInventory(player);
    }

    public long getMaxWithdrawableCustodialToInventory(Player player) {
        accountRegistryService.requirePlayerAccount(player);
        return playerEconomyService.getMaxWithdrawableToInventory(player);
    }

    public BalanceRecord creditCustodial(UUID playerUuid, String playerName, long amount, String reason) {
        accountRegistryService.requirePlayerAccount(playerUuid);
        return playerEconomyService.creditCustodial(playerUuid, playerName, amount, reason);
    }

    public Optional<AccountRecord> findAccount(UUID accountId) {
        return accountRegistryService.findAccount(accountId);
    }

    public Optional<AccountRecord> findSharedAccount(String bankName) {
        return accountRegistryService.findSharedAccount(bankName);
    }

    public Optional<String> getAccountName(UUID accountId) {
        return accountRegistryService.getAccountName(accountId);
    }

    public MoneyOperationResult bankDeposit(String bankName, OfflinePlayer owner, long amount, String reason) {
        return sharedAccountService.bankDeposit(bankName, owner, amount, reason);
    }

    public MoneyOperationResult bankWithdraw(String bankName, long amount, String reason) {
        return sharedAccountService.bankWithdraw(bankName, amount, reason);
    }

    public long bankBalance(String bankName) {
        return sharedAccountService.bankBalance(bankName);
    }

    public List<String> listBanks() {
        return accountRegistryService.listBanks();
    }

    public List<AccountRecord> listSharedAccounts() {
        return accountRegistryService.listSharedAccounts();
    }

    public Map<UUID, String> accountNameMap() {
        return accountRegistryService.accountNameMap();
    }

    public Optional<AccountRecord> findAccountByName(String name) {
        return accountRegistryService.findAccountByName(name);
    }

    public boolean isPlayerLocked(UUID playerUuid) {
        return playerEconomyService.isPlayerLocked(playerUuid);
    }

    public long getPendingIncomingPaymentBalance(UUID playerUuid) {
        return playerEconomyService.getPendingIncomingPaymentBalance(playerUuid);
    }

    public boolean renameAccount(UUID accountId, String name) {
        return accountRegistryService.renameAccount(accountId, name);
    }

    public boolean deleteSharedAccount(UUID accountId) {
        return accountRegistryService.deleteSharedAccount(accountId);
    }

    public long getBalance(UUID accountId) {
        AccountRecord account = accountRegistryService.requireAccount(accountId);
        if (account.accountType() == AccountType.PLAYER) {
            return getBalance(Bukkit.getOfflinePlayer(accountId));
        }
        return sharedAccountService.getBalance(accountId);
    }

    public long getSharedAccountBalance(UUID accountId) {
        return sharedAccountService.getBalance(accountId);
    }

    public boolean hasEnough(UUID accountId, long amount) {
        AccountRecord account = accountRegistryService.requireAccount(accountId);
        if (account.accountType() == AccountType.PLAYER) {
            return hasEnough(Bukkit.getOfflinePlayer(accountId), amount);
        }
        return sharedAccountService.hasEnough(accountId, amount);
    }

    public boolean sharedAccountHasEnough(UUID accountId, long amount) {
        return sharedAccountService.hasEnough(accountId, amount);
    }

    public MoneyOperationResult withdrawAccount(UUID accountId, long amount, String reason) {
        Optional<AccountRecord> account = accountRegistryService.findAccount(accountId);
        if (account.isEmpty()) {
            return MoneyOperationResult.failure(
                    amount,
                    "Account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        if (account.get().accountType() == AccountType.PLAYER) {
            return withdrawPlayer(Bukkit.getOfflinePlayer(accountId), amount, reason);
        }
        return sharedAccountService.withdrawAccount(accountId, amount, reason);
    }

    public MoneyOperationResult withdrawFromSharedAccount(UUID accountId, long amount, String reason) {
        return sharedAccountService.withdrawAccount(accountId, amount, reason);
    }

    public MoneyOperationResult depositAccount(UUID accountId, long amount, String reason) {
        Optional<AccountRecord> account = accountRegistryService.findAccount(accountId);
        if (account.isEmpty()) {
            return MoneyOperationResult.failure(
                    amount,
                    "Account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        if (account.get().accountType() == AccountType.PLAYER) {
            return depositPlayer(Bukkit.getOfflinePlayer(accountId), amount, reason);
        }
        return sharedAccountService.depositAccount(accountId, amount, reason);
    }

    public MoneyOperationResult depositToSharedAccount(UUID accountId, long amount, String reason) {
        return sharedAccountService.depositAccount(accountId, amount, reason);
    }

    public boolean isAccountOwner(UUID accountId, UUID subjectUuid) {
        return sharedAccountService.isAccountOwner(accountId, subjectUuid);
    }

    public boolean setSharedAccountOwner(UUID accountId, UUID ownerUuid) {
        return sharedAccountService.setSharedAccountOwner(accountId, ownerUuid);
    }

    public boolean isAccountMember(UUID accountId, UUID subjectUuid) {
        return sharedAccountService.isAccountMember(accountId, subjectUuid);
    }

    public boolean addSharedAccountMember(UUID accountId, UUID memberUuid) {
        return sharedAccountService.addSharedAccountMember(accountId, memberUuid);
    }

    public boolean removeSharedAccountMember(UUID accountId, UUID memberUuid) {
        return sharedAccountService.removeSharedAccountMember(accountId, memberUuid);
    }

    public DenominationService denominationService() {
        return denominationService;
    }

    public EconomyLoggers loggers() {
        return loggers;
    }
}
