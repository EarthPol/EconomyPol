package com.earthpol.economyPol.logging;

import com.earthpol.earthPolLib.logging.EnhancedLogger;

public record EconomyLoggers(EnhancedLogger operations, EnhancedLogger audit, EnhancedLogger healthcheck) {}
