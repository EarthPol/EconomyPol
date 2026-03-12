package com.earthpol.economyPol.vault;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.EconomyPol;
import com.earthpol.economyPol.config.PluginSettings;
import com.earthpol.economyPol.model.MoneyOperationResult;
import com.earthpol.economyPol.service.EconomyService;
import com.earthpol.economyPol.service.NumericalConsistencyService;
import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class VaultUnlockedEconomyAdapter implements Economy {

    private static final EnumSet<AccountPermission> BASIC_MEMBER_PERMISSIONS = EnumSet.of(
            AccountPermission.BALANCE,
            AccountPermission.DEPOSIT,
            AccountPermission.WITHDRAW
    );

    private final EconomyPol plugin;
    private final EconomyService economyService;
    private final NumericalConsistencyService numericalConsistencyService;
    private final PluginSettings.CurrencySettings currencySettings;
    private final EnhancedLogger logger;
    private final String defaultCurrencyId;

    public VaultUnlockedEconomyAdapter(
            EconomyPol plugin,
            EconomyService economyService,
            NumericalConsistencyService numericalConsistencyService,
            PluginSettings.CurrencySettings currencySettings,
            EnhancedLogger logger
    ) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.numericalConsistencyService = numericalConsistencyService;
        this.currencySettings = currencySettings;
        this.logger = logger;
        this.defaultCurrencyId = sanitizeCurrencyId(currencySettings.singularName());
    }

    @Override
    public boolean isEnabled() {
        return plugin.isEnabled();
    }

    @Override
    public String getName() {
        return plugin.getName() + "-VaultUnlocked";
    }

    @Override
    public boolean hasSharedAccountSupport() {
        return true;
    }

    @Override
    public boolean hasMultiCurrencySupport() {
        return false;
    }

    @Override
    public int fractionalDigits(String pluginName) {
        return numericalConsistencyService.fractionalDigits();
    }

    @Override
    public String format(BigDecimal amount) {
        return numericalConsistencyService.format(amount);
    }

    @Override
    public String format(String pluginName, BigDecimal amount) {
        return format(amount);
    }

    @Override
    public String format(BigDecimal amount, String currency) {
        return supportsCurrency(currency) ? format(amount) : amount.toPlainString() + " " + currency;
    }

    @Override
    public String format(String pluginName, BigDecimal amount, String currency) {
        return format(amount, currency);
    }

    @Override
    public boolean hasCurrency(String currency) {
        return supportsCurrency(currency);
    }

    @Override
    public String getDefaultCurrency(String pluginName) {
        return defaultCurrencyId;
    }

    @Override
    public String defaultCurrencyNamePlural(String pluginName) {
        return currencySettings.pluralName();
    }

    @Override
    public String defaultCurrencyNameSingular(String pluginName) {
        return currencySettings.singularName();
    }

    @Override
    public Collection<String> currencies() {
        return List.of(defaultCurrencyId);
    }

    @Override
    public boolean createAccount(UUID accountID, String name) {
        return createAccount(accountID, name, true);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, boolean player) {
        if (accountID == null || name == null || name.isBlank()) {
            return false;
        }
        if (player) {
            economyService.ensurePlayerAccount(accountID, name);
            return true;
        }
        // Generic shared-account creation does not supply a separate human owner.
        // Use the shared account UUID itself as the stable non-null owner identity.
        return economyService.createSharedAccount(accountID, name, accountID);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, String worldName) {
        return createAccount(accountID, name, true);
    }

    @Override
    public boolean createAccount(UUID accountID, String name, String worldName, boolean player) {
        return createAccount(accountID, name, player);
    }

    @Override
    public Map<UUID, String> getUUIDNameMap() {
        return economyService.accountNameMap();
    }

    @Override
    public Optional<String> getAccountName(UUID accountID) {
        return economyService.getAccountName(accountID);
    }

    @Override
    public boolean hasAccount(UUID accountID) {
        return economyService.findAccount(accountID).isPresent();
    }

    @Override
    public boolean hasAccount(UUID accountID, String worldName) {
        return hasAccount(accountID);
    }

    @Override
    public boolean renameAccount(UUID accountID, String name) {
        return economyService.renameAccount(accountID, name);
    }

    @Override
    public boolean renameAccount(String pluginName, UUID accountID, String name) {
        boolean renamed = economyService.renameAccount(accountID, name);
        if (renamed) {
            logger.info("vault2-rename plugin=" + pluginName + " account=" + accountID + " name=" + name);
        }
        return renamed;
    }

    @Override
    public boolean deleteAccount(String pluginName, UUID accountID) {
        boolean deleted = economyService.deleteSharedAccount(accountID);
        if (deleted) {
            logger.info("vault2-delete plugin=" + pluginName + " account=" + accountID);
        }
        return deleted;
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency) {
        return hasAccount(accountID) && supportsCurrency(currency);
    }

    @Override
    public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency, String world) {
        return accountSupportsCurrency(pluginName, accountID, currency);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID) {
        return numericalConsistencyService.toBigDecimal(economyService.getBalance(accountID));
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID, String world) {
        return getBalance(pluginName, accountID);
    }

    @Override
    public BigDecimal getBalance(String pluginName, UUID accountID, String world, String currency) {
        return supportsCurrency(currency) ? getBalance(pluginName, accountID) : BigDecimal.ZERO;
    }

    @Override
    public boolean has(String pluginName, UUID accountID, BigDecimal amount) {
        NumericalConsistencyService.ConversionResult conversion = numericalConsistencyService.toWholeUnits(amount);
        return conversion.success() && economyService.hasEnough(accountID, conversion.units());
    }

    @Override
    public boolean has(String pluginName, UUID accountID, String world, BigDecimal amount) {
        return has(pluginName, accountID, amount);
    }

    @Override
    public boolean has(String pluginName, UUID accountID, String world, String currency, BigDecimal amount) {
        return supportsCurrency(currency) && has(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, BigDecimal amount) {
        if (!hasAccount(accountID)) {
            return response(BigDecimal.ZERO, BigDecimal.ZERO, EconomyResponse.ResponseType.FAILURE, "Account does not exist.");
        }
        NumericalConsistencyService.ConversionResult conversion = numericalConsistencyService.toWholeUnits(amount);
        if (!conversion.success()) {
            return invalidAmountResponse(accountID, amount, conversion.message());
        }
        MoneyOperationResult result = economyService.withdrawAccount(accountID, conversion.units(), "VAULT2_WITHDRAW:" + pluginName);
        return toResponse(accountID, result);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return withdraw(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        if (!supportsCurrency(currency)) {
            return response(BigDecimal.ZERO, getBalance(pluginName, accountID), EconomyResponse.ResponseType.FAILURE, "Unsupported currency.");
        }
        return withdraw(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, BigDecimal amount) {
        if (!hasAccount(accountID)) {
            return response(BigDecimal.ZERO, BigDecimal.ZERO, EconomyResponse.ResponseType.FAILURE, "Account does not exist.");
        }
        NumericalConsistencyService.ConversionResult conversion = numericalConsistencyService.toWholeUnits(amount);
        if (!conversion.success()) {
            return invalidAmountResponse(accountID, amount, conversion.message());
        }
        MoneyOperationResult result = economyService.depositAccount(accountID, conversion.units(), "VAULT2_DEPOSIT:" + pluginName);
        return toResponse(accountID, result);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
        return deposit(pluginName, accountID, amount);
    }

    @Override
    public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
        if (!supportsCurrency(currency)) {
            return response(BigDecimal.ZERO, getBalance(pluginName, accountID), EconomyResponse.ResponseType.FAILURE, "Unsupported currency.");
        }
        return deposit(pluginName, accountID, amount);
    }

    @Override
    public boolean createSharedAccount(String pluginName, UUID accountID, String name, UUID owner) {
        boolean created = economyService.createSharedAccount(accountID, name, owner);
        if (created) {
            logger.info("vault2-create-shared plugin=" + pluginName + " account=" + accountID + " owner=" + owner + " name=" + name);
        }
        return created;
    }

    @Override
    public boolean isAccountOwner(String pluginName, UUID accountID, UUID uuid) {
        return economyService.isAccountOwner(accountID, uuid);
    }

    @Override
    public boolean setOwner(String pluginName, UUID accountID, UUID uuid) {
        boolean updated = economyService.setSharedAccountOwner(accountID, uuid);
        if (updated) {
            logger.info("vault2-owner-update plugin=" + pluginName + " account=" + accountID + " owner=" + uuid);
        }
        return updated;
    }

    @Override
    public boolean isAccountMember(String pluginName, UUID accountID, UUID uuid) {
        return economyService.isAccountMember(accountID, uuid);
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid) {
        boolean added = economyService.addSharedAccountMember(accountID, uuid);
        if (added) {
            logger.info("vault2-member-add plugin=" + pluginName + " account=" + accountID + " member=" + uuid);
        }
        return added;
    }

    @Override
    public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid, AccountPermission... initialPermissions) {
        if (initialPermissions != null) {
            for (AccountPermission permission : initialPermissions) {
                if (permission == AccountPermission.OWNER) {
                    return setOwner(pluginName, accountID, uuid);
                }
            }
        }
        if (initialPermissions != null && initialPermissions.length > 0) {
            logger.warn("Vault2 initial member permissions are reduced to the standard member role for account " + accountID + ".");
        }
        return addAccountMember(pluginName, accountID, uuid);
    }

    @Override
    public boolean removeAccountMember(String pluginName, UUID accountID, UUID uuid) {
        boolean removed = economyService.removeSharedAccountMember(accountID, uuid);
        if (removed) {
            logger.info("vault2-member-remove plugin=" + pluginName + " account=" + accountID + " member=" + uuid);
        }
        return removed;
    }

    @Override
    public boolean hasAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission) {
        if (permission == null || uuid == null) {
            return false;
        }
        if (economyService.isAccountOwner(accountID, uuid)) {
            return true;
        }
        if (!economyService.isAccountMember(accountID, uuid)) {
            return false;
        }
        return BASIC_MEMBER_PERMISSIONS.contains(permission);
    }

    @Override
    public boolean updateAccountPermission(String pluginName, UUID accountID, UUID uuid, AccountPermission permission, boolean value) {
        if (permission == AccountPermission.OWNER && value) {
            return setOwner(pluginName, accountID, uuid);
        }
        if (permission != null && BASIC_MEMBER_PERMISSIONS.contains(permission) && value) {
            return addAccountMember(pluginName, accountID, uuid);
        }
        logger.warn("Vault2 permission update is not fully supported. plugin=" + pluginName +
                " account=" + accountID + " member=" + uuid + " permission=" + permission + " value=" + value);
        return false;
    }

    private boolean supportsCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return true;
        }
        return currency.equalsIgnoreCase(defaultCurrencyId)
                || currency.equalsIgnoreCase(currencySettings.singularName())
                || currency.equalsIgnoreCase(currencySettings.pluralName());
    }

    private EconomyResponse invalidAmountResponse(UUID accountId, BigDecimal amount, String message) {
        return response(amount, numericalConsistencyService.toBigDecimal(economyService.getBalance(accountId)), EconomyResponse.ResponseType.FAILURE, message);
    }

    private EconomyResponse toResponse(UUID accountId, MoneyOperationResult result) {
        return response(
                numericalConsistencyService.toBigDecimal(result.processedAmount()),
                numericalConsistencyService.toBigDecimal(economyService.getBalance(accountId)),
                result.success() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
                result.message()
        );
    }

    private EconomyResponse response(BigDecimal amount, BigDecimal balance, EconomyResponse.ResponseType type, String message) {
        return new EconomyResponse(amount, balance, type, message == null ? "" : message);
    }

    private static String sanitizeCurrencyId(String value) {
        String sanitized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        if (sanitized.isBlank()) {
            return "default";
        }
        return sanitized;
    }
}
