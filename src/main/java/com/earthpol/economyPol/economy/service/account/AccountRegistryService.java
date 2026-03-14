package com.earthpol.economyPol.economy.service.account;

import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.AccountType;
import com.earthpol.economyPol.economy.model.PlayerAccountPolicy;
import com.earthpol.economyPol.economy.repository.AccountRepository;
import com.earthpol.economyPol.economy.repository.PlayerRepository;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Owns account registration and lookup concerns. This keeps the core economy
 * flows from also being responsible for player/profile bootstrapping.
 */
public final class AccountRegistryService {

    private final AccountRepository accountRepository;
    private final PlayerRepository playerRepository;
    private final Supplier<PlayerAccountPolicy> playerPolicySupplier;

    public AccountRegistryService(
            AccountRepository accountRepository,
            PlayerRepository playerRepository,
            PluginSettings settings
    ) {
        this(accountRepository, playerRepository, settings::playerPolicy);
    }

    public AccountRegistryService(
            AccountRepository accountRepository,
            PlayerRepository playerRepository,
            PlayerAccountPolicy playerPolicy
    ) {
        this(accountRepository, playerRepository, () -> playerPolicy);
    }

    private AccountRegistryService(
            AccountRepository accountRepository,
            PlayerRepository playerRepository,
            Supplier<PlayerAccountPolicy> playerPolicySupplier
    ) {
        this.accountRepository = accountRepository;
        this.playerRepository = playerRepository;
        this.playerPolicySupplier = playerPolicySupplier;
    }

    public AccountRecord ensurePlayerAccount(OfflinePlayer player) {
        registerPlayer(player);
        return accountRepository.ensurePlayerAccount(player.getUniqueId(), player.getName(), playerPolicySupplier.get());
    }

    public AccountRecord ensurePlayerAccount(UUID playerUuid, String playerName) {
        registerPlayer(playerUuid, playerName);
        return accountRepository.ensurePlayerAccount(playerUuid, playerName, playerPolicySupplier.get());
    }

    public void registerPlayer(OfflinePlayer player) {
        if (player == null) {
            return;
        }
        registerPlayer(player.getUniqueId(), player.getName());
    }

    public void registerPlayer(UUID playerUuid, String playerName) {
        if (playerUuid == null) {
            return;
        }
        playerRepository.ensurePlayer(playerUuid, playerName);
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

    public Optional<AccountRecord> findAccount(UUID accountId) {
        return accountRepository.findAccount(accountId);
    }

    public Optional<AccountRecord> findSharedAccount(String bankName) {
        return accountRepository.findSharedAccount(bankName);
    }

    public Optional<AccountRecord> findSharedAccount(UUID accountId) {
        return accountRepository.findSharedAccount(accountId);
    }

    public Optional<String> getAccountName(UUID accountId) {
        return accountRepository.findAccount(accountId).map(AccountRecord::accountName);
    }

    public Map<UUID, String> accountNameMap() {
        return accountRepository.listAccountNames();
    }

    public Optional<AccountRecord> findAccountByName(String name) {
        return accountRepository.findAccountByName(name);
    }

    public List<String> listBanks() {
        return accountRepository.listSharedAccountNames();
    }

    public List<AccountRecord> listSharedAccounts() {
        return accountRepository.listSharedAccounts();
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
        Optional<AccountRecord> existing = accountRepository.findSharedAccount(accountId);
        return existing.isPresent() && accountRepository.deleteSharedAccount(accountId);
    }
}


