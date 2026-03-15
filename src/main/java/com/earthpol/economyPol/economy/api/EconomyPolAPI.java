package com.earthpol.economyPol.economy.api;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Optional;

/**
 * Native API for EconomyPol.
 *
 * <p>This is the preferred integration surface for plugins that need access to
 * EconomyPol-specific behavior such as custodial balances, reservations,
 * denomination helpers, and managed offline ender-wallet operations.</p>
 */
public interface EconomyPolAPI extends
        EconomyPolAccountAPI,
        EconomyPolPlayerAPI,
        EconomyPolReservationAPI,
        EconomyPolEnderWalletAPI,
        EconomyPolDenominationAPI {

    /**
     * Resolve the currently-registered EconomyPol API service.
     */
    static Optional<EconomyPolAPI> resolve(Plugin callerPlugin) {
        EconomyPolApiFactory factory = Bukkit.getServicesManager().load(EconomyPolApiFactory.class);
        if (factory == null) {
            return Optional.empty();
        }
        return Optional.of(factory.getInstance(callerPlugin));
    }
}
