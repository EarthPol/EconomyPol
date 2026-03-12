package com.earthpol.economyPol.economy.logging;

import com.earthpol.earthPolLib.logging.EnhancedLogger;

public record EconomyLoggers(EnhancedLogger operations, EnhancedLogger audit, EnhancedLogger healthcheck) {}
