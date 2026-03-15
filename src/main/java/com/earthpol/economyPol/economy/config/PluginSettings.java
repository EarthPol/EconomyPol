package com.earthpol.economyPol.economy.config;

import com.earthpol.earthPolLib.config.ReloadableConfigHandler;
import com.earthpol.earthPolLib.logging.LogRetentionPolicy;
import com.earthpol.economyPol.economy.model.Denomination;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class PluginSettings {

    private static final String DATABASE_FILE = "database.yml";
    private static final String CURRENCY_FILE = "currency.yml";
    private static final String RUNTIME_FILE = "config.yml";

    private final Plugin plugin;
    private final DatabaseSettings database;
    private final CurrencySettings currency;
    private final ReloadableConfigHandler<RuntimeConfigKey> runtimeConfig;

    private volatile RuntimeSettings runtime;

    private PluginSettings(
            Plugin plugin,
            DatabaseSettings database,
            CurrencySettings currency,
            ReloadableConfigHandler<RuntimeConfigKey> runtimeConfig,
            RuntimeSettings runtime
    ) {
        this.plugin = plugin;
        this.database = database;
        this.currency = currency;
        this.runtimeConfig = runtimeConfig;
        this.runtime = runtime;
    }

    public static PluginSettings load(Plugin plugin) throws IOException {
        copyBundledConfigIfMissing(plugin, DATABASE_FILE);
        copyBundledConfigIfMissing(plugin, CURRENCY_FILE);

        FileConfiguration databaseConfig = YamlConfiguration.loadConfiguration(configFile(plugin, DATABASE_FILE));
        FileConfiguration currencyConfig = YamlConfiguration.loadConfiguration(configFile(plugin, CURRENCY_FILE));
        ReloadableConfigHandler<RuntimeConfigKey> runtimeConfig =
                new ReloadableConfigHandler<>(plugin, RUNTIME_FILE, RuntimeConfigKey.class);

        DatabaseSettings database = loadDatabaseSettings(databaseConfig);
        CurrencySettings currency = loadCurrencySettings(currencyConfig);
        RuntimeSettings runtime = loadRuntimeSettings(runtimeConfig);
        return new PluginSettings(plugin, database, currency, runtimeConfig, runtime);
    }

    public RuntimeConfigReloadResult reloadRuntimeConfig() {
        try {
            boolean cleanReload = runtimeConfig.reload();
            RuntimeSettings reloaded = loadRuntimeSettings(runtimeConfig);
            runtime = reloaded;

            List<String> warnings = new ArrayList<>();
            if (!cleanReload) {
                warnings.add("One or more config.yml values were invalid and fell back to defaults. Check the server log.");
            }
            return RuntimeConfigReloadResult.success("Reloaded config.yml.", warnings);
        } catch (IOException exception) {
            return RuntimeConfigReloadResult.failure("Failed to reload config.yml: " + exception.getMessage());
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("Failed to apply config.yml reload. Keeping the previous runtime settings. Cause: " + exception.getMessage());
            return RuntimeConfigReloadResult.failure("Failed to apply config.yml. Keeping the previous runtime settings: " + exception.getMessage());
        }
    }

    public DatabaseSettings database() {
        return database;
    }

    public CurrencySettings currency() {
        return currency;
    }

    public NumericSettings numeric() {
        return runtime.numeric();
    }

    public ChangeOverflowPolicy changeOverflowPolicy() {
        return runtime.changeOverflowPolicy();
    }

    public WalletSettings wallet() {
        return runtime.wallet();
    }

    public CacheSettings cache() {
        return runtime.cache();
    }

    public LoggingSettings logging() {
        return runtime.logging();
    }

    private static DatabaseSettings loadDatabaseSettings(FileConfiguration config) {
        return new DatabaseSettings(
                config.getString("host", "127.0.0.1"),
                String.valueOf(config.get("port", 3306)),
                config.getString("name", "economypol"),
                config.getString("username", "root"),
                config.getString("password", ""),
                config.getBoolean("disable-plugin-on-failure", true)
        );
    }

    private static CurrencySettings loadCurrencySettings(FileConfiguration config) {
        List<Denomination> denominations = new ArrayList<>();
        for (ConfigurationSection section : getSectionList(config, "denominations")) {
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

        return new CurrencySettings(
                config.getString("singular-name", "Gold Coin"),
                config.getString("plural-name", "Gold Coins"),
                denominations
        );
    }

    private static RuntimeSettings loadRuntimeSettings(ReloadableConfigHandler<RuntimeConfigKey> runtimeConfig) {
        NumericSettings numeric = new NumericSettings(
                parseEnum(
                        DecimalHandlingMode.class,
                        RuntimeConfigKey.NUMERIC_DECIMAL_HANDLING.getString(),
                        RuntimeConfigKey.NUMERIC_DECIMAL_HANDLING.getPath()
                )
        );

        ChangeOverflowPolicy changeOverflowPolicy = parseEnum(
                ChangeOverflowPolicy.class,
                RuntimeConfigKey.ROUTING_CHANGE_OVERFLOW_POLICY.getString(),
                RuntimeConfigKey.ROUTING_CHANGE_OVERFLOW_POLICY.getPath()
        );

        WalletSettings wallet = new WalletSettings(
                RuntimeConfigKey.WALLET_MANAGED_ENDER_WALLET_ENABLED.getBool(),
                RuntimeConfigKey.WALLET_INCLUDE_LIVE_PLAYER_INVENTORY.getBool(),
                RuntimeConfigKey.WALLET_INCLUDE_LIVE_ENDER_CHEST.getBool()
        );

        long balanceTopTtlSeconds = RuntimeConfigKey.CACHE_BALANCE_TOP_TTL_SECONDS.getLong();
        if (balanceTopTtlSeconds <= 0L) {
            throw new IllegalArgumentException("cache.balancetop-ttl-seconds must be positive.");
        }
        CacheSettings cache = new CacheSettings(balanceTopTtlSeconds);

        LoggingSettings logging = new LoggingSettings(
                RuntimeConfigKey.LOGGING_DEBUG.getBool(),
                parseEnum(
                        LogRetentionPolicy.class,
                        RuntimeConfigKey.LOGGING_RETENTION_POLICY.getString(),
                        RuntimeConfigKey.LOGGING_RETENTION_POLICY.getPath()
                )
        );

        return new RuntimeSettings(
                numeric,
                changeOverflowPolicy,
                wallet,
                cache,
                logging
        );
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> enumType, String rawValue, String path) {
        try {
            return Enum.valueOf(enumType, rawValue.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid value '" + rawValue + "' for " + path + ".");
        }
    }

    private static File configFile(Plugin plugin, String fileName) {
        return new File(plugin.getDataFolder(), fileName);
    }

    private static void copyBundledConfigIfMissing(Plugin plugin, String fileName) {
        if (!configFile(plugin, fileName).exists()) {
            plugin.saveResource(fileName, false);
        }
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
            DecimalHandlingMode decimalHandlingMode
    ) {}

    public record WalletSettings(
            boolean managedEnderWalletEnabled,
            boolean includeLivePlayerInventory,
            boolean includeLiveEnderChest
    ) {}

    public record CacheSettings(
            long balanceTopTtlSeconds
    ) {}

    public record LoggingSettings(
            boolean debug,
            LogRetentionPolicy retentionPolicy
    ) {}

    public enum DecimalHandlingMode {
        REJECT,
        TRUNCATE
    }

    public enum ChangeOverflowPolicy {
        FAIL,
        CUSTODIAL
    }

    private record RuntimeSettings(
            NumericSettings numeric,
            ChangeOverflowPolicy changeOverflowPolicy,
            WalletSettings wallet,
            CacheSettings cache,
            LoggingSettings logging
    ) {}
}
