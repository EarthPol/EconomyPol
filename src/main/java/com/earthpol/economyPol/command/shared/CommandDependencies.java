package com.earthpol.economyPol.command.shared;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.config.PluginSettings;
import com.earthpol.economyPol.service.DatabaseCheckService;
import com.earthpol.economyPol.service.EconomyService;
import com.earthpol.economyPol.service.EnderWalletService;
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
