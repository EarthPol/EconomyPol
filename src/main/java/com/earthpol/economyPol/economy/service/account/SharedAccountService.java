package com.earthpol.economyPol.economy.service.account;

import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.logging.EconomyLoggers.LogType;
import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.AccountType;
import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.model.MoneyOperationFailureReason;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.repository.AccountRepository;
import com.earthpol.economyPol.economy.repository.FundsRepository;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.Optional;
import java.util.UUID;

/**
 * Owns shared-account and legacy bank behavior. This isolates Towny/Vault bank
 * flows from player-specific inventory-backed money logic.
 */
public final class SharedAccountService {

    private final AccountRegistryService accountRegistryService;
    private final AccountRepository accountRepository;
    private final FundsRepository fundsRepository;
    private final EconomyLoggers loggers;

    public SharedAccountService(
            AccountRegistryService accountRegistryService,
            AccountRepository accountRepository,
            FundsRepository fundsRepository,
            EconomyLoggers loggers
    ) {
        this.accountRegistryService = accountRegistryService;
        this.accountRepository = accountRepository;
        this.fundsRepository = fundsRepository;
        this.loggers = loggers;
    }

    public MoneyOperationResult bankDeposit(String bankName, OfflinePlayer owner, long amount, String reason) {
        if (owner != null) {
            accountRegistryService.registerPlayer(owner);
        }
        Optional<AccountRecord> account = accountRegistryService.findSharedAccount(bankName);
        if (account.isEmpty()) {
            return MoneyOperationResult.failure(
                    amount,
                    "Bank account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        fundsRepository.changeAvailable(
                account.get().accountId(),
                amount,
                "BANK_DEPOSIT",
                reason,
                owner == null ? null : owner.getUniqueId(),
                null
        );
        return MoneyOperationResult.success(amount, amount, 0L, "Bank deposit completed.");
    }

    public MoneyOperationResult bankWithdraw(String bankName, long amount, String reason) {
        Optional<AccountRecord> account = accountRegistryService.findSharedAccount(bankName);
        if (account.isEmpty()) {
            return MoneyOperationResult.failure(
                    amount,
                    "Bank account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        return withdrawSharedAccount(account.get().accountId(), amount, "BANK_WITHDRAW", reason);
    }

    public long bankBalance(String bankName) {
        return accountRegistryService.findSharedAccount(bankName)
                .map(accountRecord -> fundsRepository.getBalance(accountRecord.accountId()).availableBalance())
                .orElse(0L);
    }

    public long getBalance(UUID accountId) {
        accountRegistryService.requireSharedAccount(accountId);
        return fundsRepository.getBalance(accountId).availableBalance();
    }

    public boolean hasEnough(UUID accountId, long amount) {
        accountRegistryService.requireSharedAccount(accountId);
        return fundsRepository.getBalance(accountId).availableBalance() >= amount;
    }

    public MoneyOperationResult withdrawAccount(UUID accountId, long amount, String reason) {
        return withdrawSharedAccount(accountId, amount, "SHARED_WITHDRAW", reason);
    }

    public MoneyOperationResult depositAccount(UUID accountId, long amount, String reason) {
        Optional<AccountRecord> account = accountRegistryService.findSharedAccount(accountId);
        if (account.isEmpty()) {
            return MoneyOperationResult.failure(
                    amount,
                    "Account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        fundsRepository.changeAvailable(accountId, amount, "SHARED_DEPOSIT", reason, null, null);
        loggers.log("shared-deposit account=" + accountId + " amount=" + amount + " reason=" + reason, LogType.AUDIT);
        return MoneyOperationResult.success(amount, amount, 0L, "Funds deposited.");
    }

    public boolean isAccountOwner(UUID accountId, UUID subjectUuid) {
        Optional<AccountRecord> account = accountRegistryService.findAccount(accountId);
        if (account.isEmpty() || subjectUuid == null) {
            return false;
        }
        if (account.get().accountType() == AccountType.PLAYER) {
            return accountId.equals(subjectUuid);
        }
        return subjectUuid.equals(account.get().ownerUuid());
    }

    public boolean setSharedAccountOwner(UUID accountId, UUID ownerUuid) {
        Optional<AccountRecord> account = accountRegistryService.findSharedAccount(accountId);
        if (account.isEmpty()) {
            return false;
        }
        return accountRepository.updateSharedAccountOwner(accountId, ownerUuid);
    }

    public boolean isAccountMember(UUID accountId, UUID subjectUuid) {
        if (isAccountOwner(accountId, subjectUuid)) {
            return true;
        }
        return accountRepository.isAccountMember(accountId, subjectUuid);
    }

    public boolean addSharedAccountMember(UUID accountId, UUID memberUuid) {
        Optional<AccountRecord> account = accountRegistryService.findSharedAccount(accountId);
        if (account.isEmpty() || memberUuid == null || isAccountOwner(accountId, memberUuid)) {
            return false;
        }
        accountRegistryService.registerPlayer(Bukkit.getOfflinePlayer(memberUuid));
        accountRepository.upsertAccountMember(accountId, memberUuid);
        loggers.log("shared-member-add account=" + accountId + " member=" + memberUuid, LogType.AUDIT);
        return true;
    }

    public boolean removeSharedAccountMember(UUID accountId, UUID memberUuid) {
        Optional<AccountRecord> account = accountRegistryService.findSharedAccount(accountId);
        if (account.isEmpty() || memberUuid == null || isAccountOwner(accountId, memberUuid)) {
            return false;
        }
        boolean removed = accountRepository.removeAccountMember(accountId, memberUuid);
        if (removed) {
            loggers.log("shared-member-remove account=" + accountId + " member=" + memberUuid, LogType.AUDIT);
        }
        return removed;
    }

    private MoneyOperationResult withdrawSharedAccount(UUID accountId, long amount, String entryType, String reason) {
        Optional<AccountRecord> account = accountRegistryService.findSharedAccount(accountId);
        if (account.isEmpty()) {
            return MoneyOperationResult.failure(
                    amount,
                    "Account does not exist.",
                    MoneyOperationFailureReason.ACCOUNT_NOT_FOUND
            );
        }
        BalanceRecord balance = fundsRepository.getBalance(accountId);
        if (balance.availableBalance() < amount) {
            return MoneyOperationResult.failure(
                    amount,
                    "Insufficient funds.",
                    MoneyOperationFailureReason.INSUFFICIENT_FUNDS
            );
        }
        fundsRepository.changeAvailable(accountId, -amount, entryType, reason, null, null);
        if ("BANK_WITHDRAW".equals(entryType)) {
            return MoneyOperationResult.success(amount, amount, 0L, "Bank withdrawal completed.");
        }
        loggers.log("shared-withdraw account=" + accountId + " amount=" + amount + " reason=" + reason, LogType.AUDIT);
        return MoneyOperationResult.success(amount, amount, 0L, "Funds withdrawn.");
    }
}


