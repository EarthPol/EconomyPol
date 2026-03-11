package com.earthpol.economyPol.command.shared;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.config.PluginSettings;
import com.earthpol.economyPol.service.DatabaseCheckService;
import com.earthpol.economyPol.service.EconomyService;
import com.earthpol.economyPol.service.EnderWalletService;

public record CommandDependencies(
        EconomyService economyService,
        EnderWalletService enderWalletService,
        DatabaseCheckService databaseCheckService,
        PluginSettings settings,
        EnhancedLogger operationsLogger,
        EnhancedLogger healthcheckLogger
) {}
