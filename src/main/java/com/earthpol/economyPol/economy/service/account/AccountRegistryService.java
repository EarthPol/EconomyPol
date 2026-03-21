package com.earthpol.economyPol.economy.service.account;

import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.AccountType;
import com.earthpol.economyPol.economy.model.IncomingPaymentDeliveryPreference;
import com.earthpol.economyPol.economy.repository.AccountRepository;
import com.earthpol.economyPol.economy.repository.PlayerRepository;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns account registration and lookup concerns. This keeps the core economy
 * flows from also being responsible for player/profile bootstrapping.
 */
public final class AccountRegistryService {

    private final AccountRepository accountRepository;
    private final PlayerRepository playerRepository;

    public AccountRegistryService(
            AccountRepository accountRepository,
            PlayerRepository playerRepository,
            PluginSettings settings
    ) {
        this.accountRepository = accountRepository;
        this.playerRepository = playerRepository;
    }

    public AccountRecord ensurePlayerAccount(OfflinePlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("Player cannot be null.");
        }
        validatePlayerAccountStorageNameAvailable(player.getUniqueId());
        registerPlayer(player);
        return accountRepository.ensurePlayerAccount(player.getUniqueId(), player.getName());
    }

    public AccountRecord ensurePlayerAccount(UUID playerUuid, String playerName) {
        if (playerUuid == null) {
            throw new IllegalArgumentException("Player UUID cannot be null.");
        }
        validatePlayerAccountStorageNameAvailable(playerUuid);
        registerPlayer(playerUuid, playerName);
        return accountRepository.ensurePlayerAccount(playerUuid, playerName);
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

    public void syncPlayerIdentity(OfflinePlayer player) {
        if (player == null) {
            return;
        }
        registerPlayer(player);
        Optional<AccountRecord> existingAccount = findPlayerAccount(player.getUniqueId());
        if (existingAccount.isPresent() && !Objects.equals(existingAccount.get().accountName(), stablePlayerAccountName(player.getUniqueId()))) {
            validatePlayerAccountStorageNameAvailable(player.getUniqueId());
            accountRepository.ensurePlayerAccount(player.getUniqueId(), player.getName());
        }
    }

    public IncomingPaymentDeliveryPreference getIncomingPaymentDeliveryPreference(UUID playerUuid) {
        requirePlayerAccount(playerUuid);
        IncomingPaymentDeliveryPreference preference = playerRepository.getIncomingPaymentDeliveryPreference(playerUuid);
        return preference == null ? IncomingPaymentDeliveryPreference.DEFAULT : preference;
    }

    public Optional<String> resolvePlayerUsername(UUID playerUuid) {
        if (playerUuid == null) {
            return Optional.empty();
        }
        Player onlinePlayer = Bukkit.getPlayer(playerUuid);
        if (onlinePlayer != null) {
            String playerName = onlinePlayer.getName();
            if (playerName != null && !playerName.isBlank()) {
                return Optional.of(playerName);
            }
        }
        return playerRepository.findUsername(playerUuid);
    }

    public IncomingPaymentDeliveryPreference setIncomingPaymentDeliveryPreference(
            UUID playerUuid,
            String playerName,
            IncomingPaymentDeliveryPreference preference
    ) {
        requirePlayerAccount(playerUuid);
        playerRepository.setIncomingPaymentDeliveryPreference(playerUuid, preference);
        return preference;
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

    public Optional<AccountRecord> findPlayerAccount(UUID playerUuid) {
        return accountRepository.findPlayerAccount(playerUuid);
    }

    public Optional<AccountRecord> findPlayerAccount(OfflinePlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        return findPlayerAccount(player.getUniqueId());
    }

    public Optional<AccountRecord> findSharedAccount(String bankName) {
        return accountRepository.findSharedAccount(bankName);
    }

    public Optional<AccountRecord> findSharedAccount(UUID accountId) {
        return accountRepository.findSharedAccount(accountId);
    }

    public AccountRecord requirePlayerAccount(OfflinePlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("Player cannot be null.");
        }
        return requirePlayerAccount(player.getUniqueId());
    }

    public AccountRecord requirePlayerAccount(UUID playerUuid) {
        if (playerUuid == null) {
            throw new IllegalArgumentException("Player UUID cannot be null.");
        }
        return findPlayerAccount(playerUuid)
                .orElseThrow(() -> new IllegalStateException("Player account does not exist: " + playerUuid));
    }

    public AccountRecord requireAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("Account UUID cannot be null.");
        }
        return findAccount(accountId)
                .orElseThrow(() -> new IllegalStateException("Account does not exist: " + accountId));
    }

    public AccountRecord requireSharedAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("Shared account UUID cannot be null.");
        }
        return findSharedAccount(accountId)
                .orElseThrow(() -> new IllegalStateException("Shared account does not exist: " + accountId));
    }

    public Optional<String> getAccountName(UUID accountId) {
        Optional<AccountRecord> account = accountRepository.findAccount(accountId);
        if (account.isEmpty()) {
            return Optional.empty();
        }
        if (account.get().accountType() != AccountType.PLAYER) {
            return Optional.of(account.get().accountName());
        }
        return resolvePlayerUsername(accountId).or(() -> Optional.of(account.get().accountName()));
    }

    public Map<UUID, String> accountNameMap() {
        Map<UUID, String> storedUsernames = playerRepository.listUsernames();
        java.util.LinkedHashMap<UUID, String> names = new java.util.LinkedHashMap<>();
        for (AccountRecord account : accountRepository.listAccounts()) {
            if (account.accountType() != AccountType.PLAYER) {
                names.put(account.accountId(), account.accountName());
                continue;
            }
            Player onlinePlayer = Bukkit.getPlayer(account.accountId());
            if (onlinePlayer != null && onlinePlayer.getName() != null && !onlinePlayer.getName().isBlank()) {
                names.put(account.accountId(), onlinePlayer.getName());
                continue;
            }
            names.put(account.accountId(), storedUsernames.getOrDefault(account.accountId(), account.accountName()));
        }
        return Map.copyOf(names);
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
        if (existing.get().accountType() == AccountType.PLAYER) {
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

    private String stablePlayerAccountName(UUID playerUuid) {
        return playerUuid.toString();
    }

    private void validatePlayerAccountStorageNameAvailable(UUID playerUuid) {
        String storageName = stablePlayerAccountName(playerUuid);
        Optional<AccountRecord> collision = accountRepository.findAccountByName(storageName);
        if (collision.isPresent() && !collision.get().accountId().equals(playerUuid)) {
            throw new IllegalStateException("Player account storage name is already used by another account: " + storageName);
        }
    }
}


