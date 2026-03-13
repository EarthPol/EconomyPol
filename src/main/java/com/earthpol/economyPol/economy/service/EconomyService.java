package com.earthpol.economyPol.economy.service;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.AccountType;
import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.model.PlayerBalanceView;
import com.earthpol.economyPol.economy.repository.AccountRepository;
import com.earthpol.economyPol.economy.repository.FundsRepository;
import com.earthpol.economyPol.economy.repository.PlayerRepository;
import com.earthpol.economyPol.economy.service.account.AccountRegistryService;
import com.earthpol.economyPol.economy.service.account.SharedAccountService;
import com.earthpol.economyPol.economy.service.money.LiveMoneyService;
import com.earthpol.economyPol.economy.service.player.EnderWalletService;
import com.earthpol.economyPol.economy.service.player.NotificationService;
import com.earthpol.economyPol.economy.service.player.PlayerEconomyService;
import com.earthpol.economyPol.economy.service.player.PlayerMoneyLockService;
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
    private final EnhancedLogger operationsLog;

    private final AccountRegistryService accountRegistryService;
    private final PlayerEconomyService playerEconomyService;
    private final SharedAccountService sharedAccountService;

    public EconomyService(
            AccountRepository accountRepository,
            PlayerRepository playerRepository,
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
        this.denominationService = denominationService;
        this.liveMoneyService = liveMoneyService;
        this.reservationService = reservationService;
        this.schedulerService = schedulerService;
        this.operationsLog = operationsLog;

        this.accountRegistryService = new AccountRegistryService(
                accountRepository,
                playerRepository,
                settings.playerPolicy()
        );
        this.playerEconomyService = new PlayerEconomyService(
                accountRegistryService,
                fundsRepository,
                liveMoneyService,
                enderWalletService,
                playerMoneyLockService,
                notificationService,
                schedulerService,
                settings,
                operationsLog,
                auditLog
        );
        this.sharedAccountService = new SharedAccountService(
                accountRegistryService,
                accountRepository,
                fundsRepository,
                auditLog
        );
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
        return accountRegistryService.ensurePlayerAccount(player);
    }

    public AccountRecord ensurePlayerAccount(UUID playerUuid, String playerName) {
        return accountRegistryService.ensurePlayerAccount(playerUuid, playerName);
    }

    public void registerPlayer(OfflinePlayer player) {
        accountRegistryService.registerPlayer(player);
    }

    public void registerPlayer(UUID playerUuid, String playerName) {
        accountRegistryService.registerPlayer(playerUuid, playerName);
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
        return playerEconomyService.balanceView(player);
    }

    public long getBalance(OfflinePlayer player) {
        return playerEconomyService.getBalance(player);
    }

    public long scanOnlinePlayerMoney(Player player) {
        return playerEconomyService.scanOnlinePlayerMoney(player);
    }

    public long getCustodialAvailable(OfflinePlayer player) {
        return playerEconomyService.getCustodialAvailable(player);
    }

    public boolean hasEnough(OfflinePlayer player, long amount) {
        return playerEconomyService.hasEnough(player, amount);
    }

    public MoneyOperationResult withdrawPlayer(OfflinePlayer player, long amount, String reason) {
        return playerEconomyService.withdrawPlayer(player, amount, reason);
    }

    public MoneyOperationResult depositPlayer(OfflinePlayer player, long amount, String reason) {
        return playerEconomyService.depositPlayer(player, amount, reason);
    }

    public MoneyOperationResult depositSelf(Player player, long amount) {
        return playerEconomyService.depositSelf(player, amount);
    }

    public MoneyOperationResult withdrawCustodialAsPhysicalMoney(Player player, long amount) {
        return playerEconomyService.withdrawCustodialAsPhysicalMoney(player, amount);
    }

    public MoneyOperationResult withdrawMaxCustodialToInventory(Player player) {
        return playerEconomyService.withdrawMaxCustodialToInventory(player);
    }

    public BalanceRecord creditCustodial(UUID playerUuid, String playerName, long amount, String reason) {
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

    public boolean renameAccount(UUID accountId, String name) {
        return accountRegistryService.renameAccount(accountId, name);
    }

    public boolean deleteSharedAccount(UUID accountId) {
        return accountRegistryService.deleteSharedAccount(accountId);
    }

    public long getBalance(UUID accountId) {
        Optional<AccountRecord> account = accountRegistryService.findAccount(accountId);
        if (account.isEmpty()) {
            return 0L;
        }
        if (account.get().accountType() == AccountType.PLAYER) {
            return getBalance(Bukkit.getOfflinePlayer(accountId));
        }
        return sharedAccountService.getBalance(accountId);
    }

    public boolean hasEnough(UUID accountId, long amount) {
        Optional<AccountRecord> account = accountRegistryService.findAccount(accountId);
        if (account.isEmpty()) {
            return false;
        }
        if (account.get().accountType() == AccountType.PLAYER) {
            return hasEnough(Bukkit.getOfflinePlayer(accountId), amount);
        }
        return sharedAccountService.hasEnough(accountId, amount);
    }

    public MoneyOperationResult withdrawAccount(UUID accountId, long amount, String reason) {
        Optional<AccountRecord> account = accountRegistryService.findAccount(accountId);
        if (account.isEmpty()) {
            return MoneyOperationResult.failure(amount, "Account does not exist.");
        }
        if (account.get().accountType() == AccountType.PLAYER) {
            return withdrawPlayer(Bukkit.getOfflinePlayer(accountId), amount, reason);
        }
        return sharedAccountService.withdrawAccount(accountId, amount, reason);
    }

    public MoneyOperationResult depositAccount(UUID accountId, long amount, String reason) {
        Optional<AccountRecord> account = accountRegistryService.findAccount(accountId);
        if (account.isEmpty()) {
            return MoneyOperationResult.failure(amount, "Account does not exist.");
        }
        if (account.get().accountType() == AccountType.PLAYER) {
            return depositPlayer(Bukkit.getOfflinePlayer(accountId), amount, reason);
        }
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

    public EnhancedLogger operationsLog() {
        return operationsLog;
    }
}
