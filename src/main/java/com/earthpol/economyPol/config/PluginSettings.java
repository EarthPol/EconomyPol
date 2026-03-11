package com.earthpol.economyPol.config;

import com.earthpol.economyPol.domain.Denomination;
import com.earthpol.economyPol.domain.MoneyRouteTarget;
import com.earthpol.economyPol.domain.PlayerAccountPolicy;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

public final class PluginSettings {

    private final DatabaseSettings database;
    private final CurrencySettings currency;
    private final NumericSettings numeric;
    private final PlayerAccountPolicy playerPolicy;
    private final List<MoneyRouteTarget> routingOrder;
    private final WalletSettings wallet;
    private final LoggingSettings logging;

    private PluginSettings(
            DatabaseSettings database,
            CurrencySettings currency,
            NumericSettings numeric,
            PlayerAccountPolicy playerPolicy,
            List<MoneyRouteTarget> routingOrder,
            WalletSettings wallet,
            LoggingSettings logging
    ) {
        this.database = database;
        this.currency = currency;
        this.numeric = numeric;
        this.playerPolicy = playerPolicy;
        this.routingOrder = List.copyOf(routingOrder);
        this.wallet = wallet;
        this.logging = logging;
    }

    public static PluginSettings load(FileConfiguration config) {
        DatabaseSettings database = new DatabaseSettings(
                config.getString("database.host", "127.0.0.1"),
                String.valueOf(config.getInt("database.port", 3306)),
                config.getString("database.name", "economypol"),
                config.getString("database.username", "root"),
                config.getString("database.password", ""),
                config.getBoolean("database.disable-plugin-on-failure", true)
        );

        List<Denomination> denominations = new ArrayList<>();
        for (ConfigurationSection section : getSectionList(config, "currency.denominations")) {
            Material material = Material.matchMaterial(section.getString("material", ""));
            if (material == null || material.isAir()) {
                throw new IllegalArgumentException("Invalid denomination material: " + section.getString("material"));
            }
            long baseUnits = section.getLong("base-units");
            if (baseUnits <= 0L) {
                throw new IllegalArgumentException("Denomination base-units must be positive for " + material);
            }
            denominations.add(new Denomination(material, baseUnits));
        }

        if (denominations.isEmpty()) {
            throw new IllegalArgumentException("At least one denomination must be configured.");
        }

        denominations.sort(Comparator.comparingLong(Denomination::baseUnits));
        validateLadder(denominations);

        CurrencySettings currency = new CurrencySettings(
                config.getString("currency.singular-name", "Gold Coin"),
                config.getString("currency.plural-name", "Gold Coins"),
                denominations
        );

        NumericSettings numeric = new NumericSettings(
                DecimalHandlingMode.valueOf(config.getString("numeric.decimal-handling", "REJECT").toUpperCase(Locale.ROOT)),
                RoundingMode.valueOf(config.getString("numeric.rounding-mode", "HALF_UP").toUpperCase(Locale.ROOT))
        );

        PlayerAccountPolicy playerPolicy = new PlayerAccountPolicy(
                config.getBoolean("players.allow-self-deposit", false),
                config.getBoolean("players.allow-external-credit", true),
                config.getBoolean("players.allow-self-withdraw", true)
        );

        List<MoneyRouteTarget> routingOrder = new ArrayList<>();
        for (String routeName : config.getStringList("routing.order")) {
            routingOrder.add(MoneyRouteTarget.valueOf(routeName.toUpperCase()));
        }
        validateRoutingOrder(routingOrder);

        WalletSettings wallet = new WalletSettings(
                config.getBoolean("wallet.managed-ender-wallet-enabled", true),
                config.getBoolean("wallet.include-live-player-inventory", true),
                config.getBoolean("wallet.include-live-ender-chest", true)
        );

        LoggingSettings logging = new LoggingSettings(
                config.getBoolean("logging.debug", false),
                config.getString("logging.audit-log-name", "audit"),
                config.getString("logging.operations-log-name", "operations")
        );

        return new PluginSettings(database, currency, numeric, playerPolicy, routingOrder, wallet, logging);
    }

    private static List<ConfigurationSection> getSectionList(FileConfiguration config, String path) {
        List<ConfigurationSection> sections = new ArrayList<>();
        for (Object object : config.getList(path, List.of())) {
            if (object instanceof ConfigurationSection section) {
                sections.add(section);
            } else if (object instanceof java.util.Map<?, ?> rawMap) {
                org.bukkit.configuration.MemoryConfiguration wrapper = new org.bukkit.configuration.MemoryConfiguration();
                for (var entry : rawMap.entrySet()) {
                    if (entry.getKey() != null) {
                        wrapper.set(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                sections.add(wrapper);
            }
        }
        return sections;
    }

    private static void validateRoutingOrder(List<MoneyRouteTarget> routingOrder) {
        if (routingOrder.isEmpty()) {
            throw new IllegalArgumentException("routing.order must not be empty.");
        }
        EnumSet<MoneyRouteTarget> seen = EnumSet.noneOf(MoneyRouteTarget.class);
        for (MoneyRouteTarget target : routingOrder) {
            if (!seen.add(target)) {
                throw new IllegalArgumentException("routing.order contains duplicate target " + target);
            }
        }
        if (routingOrder.get(routingOrder.size() - 1) != MoneyRouteTarget.CUSTODIAL_ACCOUNT) {
            throw new IllegalArgumentException("routing.order must end with CUSTODIAL_ACCOUNT.");
        }
    }

    private static void validateLadder(List<Denomination> denominations) {
        long previous = 0L;
        for (Denomination denomination : denominations) {
            if (previous != 0L && denomination.baseUnits() % previous != 0L) {
                throw new IllegalArgumentException(
                        "Denomination ladder is invalid. " + denomination.material() +
                                " base-units must be divisible by " + previous
                );
            }
            previous = denomination.baseUnits();
        }
    }

    public DatabaseSettings database() {
        return database;
    }

    public CurrencySettings currency() {
        return currency;
    }

    public NumericSettings numeric() {
        return numeric;
    }

    public PlayerAccountPolicy playerPolicy() {
        return playerPolicy;
    }

    public List<MoneyRouteTarget> routingOrder() {
        return routingOrder;
    }

    public WalletSettings wallet() {
        return wallet;
    }

    public LoggingSettings logging() {
        return logging;
    }

    public record DatabaseSettings(
            String host,
            String port,
            String name,
            String username,
            String password,
            boolean disablePluginOnFailure
    ) {}

    public record CurrencySettings(
            String singularName,
            String pluralName,
            List<Denomination> denominations
    ) {}

    public record NumericSettings(
            DecimalHandlingMode decimalHandlingMode,
            RoundingMode roundingMode
    ) {}

    public record WalletSettings(
            boolean managedEnderWalletEnabled,
            boolean includeLivePlayerInventory,
            boolean includeLiveEnderChest
    ) {}

    public record LoggingSettings(
            boolean debug,
            String auditLogName,
            String operationsLogName
    ) {}

    public enum DecimalHandlingMode {
        REJECT,
        ROUND,
        TRUNCATE
    }
}
