package com.earthpol.economyPol.economy.api;

import org.bukkit.plugin.Plugin;

public interface EconomyPolApiFactory {

    EconomyPolAPI getInstance(Plugin callerPlugin);
}
