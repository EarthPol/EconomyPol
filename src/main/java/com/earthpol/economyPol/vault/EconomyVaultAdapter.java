package com.earthpol.economyPol.vault;

import com.earthpol.economyPol.EconomyPol;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.logging.EconomyLoggers.LogType;
import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.AccountType;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.support.NumericalConsistencyService;
import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class EconomyVaultAdapter extends AbstractEconomy {

    private final EconomyPol plugin;
    private final EconomyService economyService;
    private final NumericalConsistencyService numericalConsistencyService;
    private final PluginSettings.CurrencySettings currencySettings;
    private final EconomyLoggers loggers;

    public EconomyVaultAdapter(
            EconomyPol plugin,
            EconomyService economyService,
            NumericalConsistencyService numericalConsistencyService,
            PluginSettings.CurrencySettings currencySettings,
            EconomyLoggers loggers
    ) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.numericalConsistencyService = numericalConsistencyService;
        this.currencySettings = currencySettings;
        this.loggers = loggers;
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

    @Override
    public String getName() {
        return plugin.getName();
    }

    @Override
    public boolean hasBankSupport() {
        return true;
    }

    @Override
    public int fractionalDigits() {
        return numericalConsistencyService.fractionalDigits();
    }

    @Override
    public String format(double amount) {
        return numericalConsistencyService.format(amount);
    }

    @Override
    public String currencyNamePlural() {
        return currencySettings.pluralName();
    }

    @Override
    public String currencyNameSingular() {
        return currencySettings.singularName();
    }

    @Override
    public boolean hasAccount(String playerName) {
        return findExistingPlayerAccount(playerName).isPresent();
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return findExistingPlayerAccount(player).isPresent();
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public double getBalance(String playerName) {
        return findExistingPlayerAccount(playerName)
                .map(account -> numericalConsistencyService.toDouble(economyService.getBalance(account.accountId())))
                .orElse(0D);
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return findExistingPlayerAccount(player)
                .map(account -> numericalConsistencyService.toDouble(economyService.getBalance(account.accountId())))
                .orElse(0D);
    }

    @Override
    public double getBalance(String playerName, String worldName) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String worldName) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        NumericalConsistencyService.ConversionResult conversion = numericalConsistencyService.toWholeUnits(amount);
        return conversion.success() && findExistingPlayerAccount(playerName)
                .map(account -> economyService.hasEnough(account.accountId(), conversion.units()))
                .orElse(false);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        NumericalConsistencyService.ConversionResult conversion = numericalConsistencyService.toWholeUnits(amount);
        return conversion.success() && findExistingPlayerAccount(player)
                .map(account -> economyService.hasEnough(account.accountId(), conversion.units()))
                .orElse(false);
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        NumericalConsistencyService.ConversionResult conversion = numericalConsistencyService.toWholeUnits(amount);
        if (!conversion.success()) {
            return failure(amount, conversion.message());
        }
        return resolvePlayer(playerName)
                .map(player -> toResponse(economyService.withdrawPlayer(player, conversion.units(), "VAULT_WITHDRAW"), player))
                .orElse(failure(amount, "Unknown player."));
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        NumericalConsistencyService.ConversionResult conversion = numericalConsistencyService.toWholeUnits(amount);
        if (!conversion.success()) {
            return player == null
                    ? failure(amount, conversion.message())
                    : invalidAmountResponse(player, amount, conversion.message());
        }
        return player == null
                ? failure(amount, "Unknown player.")
                : toResponse(economyService.withdrawPlayer(player, conversion.units(), "VAULT_WITHDRAW"), player);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        NumericalConsistencyService.ConversionResult conversion = numericalConsistencyService.toWholeUnits(amount);
        if (!conversion.success()) {
            return failure(amount, conversion.message());
        }
        return resolvePlayer(playerName)
                .map(player -> toResponse(economyService.depositPlayer(player, conversion.units(), "VAULT_DEPOSIT"), player))
                .orElse(failure(amount, "Unknown player."));
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        NumericalConsistencyService.ConversionResult conversion = numericalConsistencyService.toWholeUnits(amount);
        if (!conversion.success()) {
            return player == null
                    ? failure(amount, conversion.message())
                    : invalidAmountResponse(player, amount, conversion.message());
        }
        return player == null
                ? failure(amount, "Unknown player.")
                : toResponse(economyService.depositPlayer(player, conversion.units(), "VAULT_DEPOSIT"), player);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        OfflinePlayer owner = resolvePlayer(player).orElse(null);
        economyService.ensureSharedAccount(name, owner);
        return new EconomyResponse(0D, economyService.bankBalance(name), EconomyResponse.ResponseType.SUCCESS, "");
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        Optional<AccountRecord> bank = economyService.findSharedAccount(name);
        if (bank.isEmpty()) {
            return new EconomyResponse(0D, 0D, EconomyResponse.ResponseType.FAILURE, "Bank does not exist.");
        }
        boolean deleted = economyService.deleteSharedAccount(bank.get().accountId());
        return new EconomyResponse(
                0D,
                deleted ? 0D : economyService.bankBalance(name),
                deleted ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
                deleted ? "" : "Bank deletion failed."
        );
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return new EconomyResponse(0D, economyService.bankBalance(name), EconomyResponse.ResponseType.SUCCESS, "");
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        NumericalConsistencyService.ConversionResult conversion = numericalConsistencyService.toWholeUnits(amount);
        long balance = economyService.bankBalance(name);
        if (!conversion.success()) {
            return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.FAILURE, conversion.message());
        }
        return new EconomyResponse(
                amount,
                balance,
                balance >= conversion.units() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
                ""
        );
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        NumericalConsistencyService.ConversionResult conversion = numericalConsistencyService.toWholeUnits(amount);
        if (!conversion.success()) {
            return new EconomyResponse(amount, economyService.bankBalance(name), EconomyResponse.ResponseType.FAILURE, conversion.message());
        }
        MoneyOperationResult result = economyService.bankWithdraw(name, conversion.units(), "VAULT_BANK_WITHDRAW");
        return new EconomyResponse(result.processedAmount(), economyService.bankBalance(name),
                result.success() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
                result.message());
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        NumericalConsistencyService.ConversionResult conversion = numericalConsistencyService.toWholeUnits(amount);
        if (!conversion.success()) {
            return new EconomyResponse(amount, economyService.bankBalance(name), EconomyResponse.ResponseType.FAILURE, conversion.message());
        }
        MoneyOperationResult result = economyService.bankDeposit(name, null, conversion.units(), "VAULT_BANK_DEPOSIT");
        return new EconomyResponse(result.processedAmount(), economyService.bankBalance(name),
                result.success() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
                result.message());
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return resolvePlayer(playerName)
                .map(player -> isBankOwner(name, player))
                .orElse(failure(0D, "Unknown player."));
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return resolvePlayer(playerName)
                .map(player -> isBankMember(name, player))
                .orElse(failure(0D, "Unknown player."));
    }

    @Override
    public List<String> getBanks() {
        return economyService.listBanks();
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return resolvePlayer(playerName).map(economyService::ensurePlayerAccount).isPresent();
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return player != null && economyService.ensurePlayerAccount(player) != null;
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        economyService.ensureSharedAccount(name, player);
        return new EconomyResponse(0D, economyService.bankBalance(name), EconomyResponse.ResponseType.SUCCESS, "");
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        Optional<AccountRecord> bank = economyService.findSharedAccount(name);
        boolean owner = bank.isPresent() && player != null && player.getUniqueId().equals(bank.get().ownerUuid());
        return new EconomyResponse(0D, economyService.bankBalance(name), owner ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE, owner ? "" : "Not owner.");
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        Optional<AccountRecord> bank = economyService.findSharedAccount(name);
        boolean member = bank.isPresent() && player != null && economyService.isAccountMember(bank.get().accountId(), player.getUniqueId());
        return new EconomyResponse(0D, economyService.bankBalance(name), member ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE, member ? "" : "Not member.");
    }

    private Optional<OfflinePlayer> resolvePlayer(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return Optional.empty();
        }
        try {
            try {
                UUID playerUuid = UUID.fromString(playerName);
                return Optional.of(Bukkit.getOfflinePlayer(playerUuid));
            } catch (IllegalArgumentException ignored) {
            }
            return Optional.of(Bukkit.getOfflinePlayer(playerName));
        } catch (Exception exception) {
            loggers.logWarn("Failed to resolve player '" + playerName + "': " + exception.getMessage(), LogType.OPERATIONS);
            return Optional.empty();
        }
    }

    private Optional<AccountRecord> findExistingPlayerAccount(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return Optional.empty();
        }
        try {
            UUID playerUuid = UUID.fromString(playerName);
            return findExistingPlayerAccount(playerUuid);
        } catch (IllegalArgumentException ignored) {
        }

        Optional<AccountRecord> byName = economyService.findAccountByName(playerName).filter(this::isPlayerAccount);
        if (byName.isPresent()) {
            return byName;
        }
        return resolvePlayer(playerName).flatMap(this::findExistingPlayerAccount);
    }

    private Optional<AccountRecord> findExistingPlayerAccount(OfflinePlayer player) {
        if (player == null) {
            return Optional.empty();
        }
        return findExistingPlayerAccount(player.getUniqueId());
    }

    private Optional<AccountRecord> findExistingPlayerAccount(UUID playerUuid) {
        return economyService.findAccount(playerUuid).filter(this::isPlayerAccount);
    }

    private boolean isPlayerAccount(AccountRecord accountRecord) {
        return accountRecord.accountType() == AccountType.PLAYER;
    }

    private EconomyResponse toResponse(MoneyOperationResult result, OfflinePlayer player) {
        return new EconomyResponse(
                result.processedAmount(),
                economyService.getBalance(player),
                result.success() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
                result.message()
        );
    }

    private EconomyResponse invalidAmountResponse(OfflinePlayer player, double amount, String message) {
        double balance = findExistingPlayerAccount(player)
                .map(account -> numericalConsistencyService.toDouble(economyService.getBalance(account.accountId())))
                .orElse(0D);
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.FAILURE, message);
    }

    private EconomyResponse failure(double amount, String message) {
        return new EconomyResponse(amount, 0D, EconomyResponse.ResponseType.FAILURE, message);
    }
}

