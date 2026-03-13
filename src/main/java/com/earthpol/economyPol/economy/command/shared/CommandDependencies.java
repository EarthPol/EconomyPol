package com.earthpol.economyPol.economy.command.shared;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.service.databasecheck.DatabaseCheckService;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.player.EnderWalletService;
import com.earthpol.economyPol.towny.TownyService;

public record CommandDependencies(
        EconomyService economyService,
        EnderWalletService enderWalletService,
        DatabaseCheckService databaseCheckService,
        TownyService townyService,
        PluginSettings settings,
        EnhancedLogger operationsLogger,
        EnhancedLogger healthcheckLogger
) {}

