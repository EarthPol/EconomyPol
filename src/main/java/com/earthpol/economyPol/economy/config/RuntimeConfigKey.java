package com.earthpol.economyPol.economy.config;

import com.earthpol.earthPolLib.config.ReloadableConfigNode;
import com.earthpol.earthPolLib.config.ReloadableConfiguration;
import com.earthpol.earthPolLib.config.SectionHeaderNode;
import com.earthpol.earthPolLib.logging.LogRetentionPolicy;

enum RuntimeConfigKey implements ReloadableConfiguration {

    NUMERIC_SECTION(SectionHeaderNode.of(
            "numeric",
            "How external decimal amounts from Vault or VaultUnlocked are handled."
    )),
    NUMERIC_DECIMAL_HANDLING(ReloadableConfigNode.of(
            "numeric.decimal-handling",
            String.class,
            PluginSettings.DecimalHandlingMode.TRUNCATE.name(),
            "REJECT: fail on any fractional amount such as 5.5.",
            "TRUNCATE: cut toward zero; 5.9 -> 5 and -5.9 -> -5."
    )),

    ROUTING_SECTION(SectionHeaderNode.of(
            "routing",
            "How live physical money behaves when change must be returned.",
            "The canonical delivery order is hard-coded as INVENTORY -> ENDER_CHEST -> CUSTODIAL_ACCOUNT.",
            "Players can suppress the early stages of that order with /economypol paymentdelivery."
    )),
    ROUTING_CHANGE_OVERFLOW_POLICY(ReloadableConfigNode.of(
            "routing.change-overflow-policy",
            String.class,
            PluginSettings.ChangeOverflowPolicy.CUSTODIAL.name(),
            "FAIL: cancel the transaction if change cannot fully fit physically.",
            "CUSTODIAL: route the unplaceable part of change into custodial instead."
    )),

    WALLET_SECTION(SectionHeaderNode.of(
            "wallet",
            "Managed ender-wallet and live money scan behavior."
    )),
    WALLET_MANAGED_ENDER_WALLET_ENABLED(ReloadableConfigNode.of(
            "wallet.managed-ender-wallet-enabled",
            Boolean.class,
            true
    )),
    WALLET_INCLUDE_LIVE_PLAYER_INVENTORY(ReloadableConfigNode.of(
            "wallet.include-live-player-inventory",
            Boolean.class,
            true
    )),
    WALLET_INCLUDE_LIVE_ENDER_CHEST(ReloadableConfigNode.of(
            "wallet.include-live-ender-chest",
            Boolean.class,
            true
    )),

    CACHE_SECTION(SectionHeaderNode.of(
            "cache",
            "Display caches.",
            "These are informational caches and not a source of truth."
    )),
    CACHE_BALANCE_TOP_TTL_SECONDS(ReloadableConfigNode.of(
            "cache.balancetop-ttl-seconds",
            Long.class,
            60L,
            "How long /economypol balancetop stays fresh before the next request rebuilds it."
    )),

    LOGGING_SECTION(SectionHeaderNode.of(
            "logging",
            "Runtime logging controls.",
            "debug is currently reserved for future use.",
            "console-enabled controls whether the operations, audit, and healthcheck logs also print to the server console.",
            "The main log never prints to console, which prevents duplicate console lines.",
            "retention-policy applies to the main, operations, audit, and healthcheck logs."
    )),
    LOGGING_DEBUG(ReloadableConfigNode.of(
            "logging.debug",
            Boolean.class,
            false
    )),
    LOGGING_CONSOLE_ENABLED(ReloadableConfigNode.of(
            "logging.console-enabled",
            Boolean.class,
            false
    )),
    LOGGING_RETENTION_POLICY(ReloadableConfigNode.of(
            "logging.retention-policy",
            String.class,
            LogRetentionPolicy.MONTHLY.name(),
            "WEEKLY: keep about one week of *.log files.",
            "MONTHLY: keep about one month of *.log files.",
            "NEVER: do not delete old *.log files."
    ));

    private final ReloadableConfigNode<?> node;
    RuntimeConfigKey(ReloadableConfigNode<?> node) {this.node = node;}
    @Override
    public ReloadableConfigNode<?> node() {
        return node;
    }
}
