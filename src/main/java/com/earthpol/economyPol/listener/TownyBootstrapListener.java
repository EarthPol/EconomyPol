package com.earthpol.economyPol.listener;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.service.EconomyService;
import com.earthpol.economyPol.service.TownyDiagnosticsBackendImpl;
import com.earthpol.economyPol.service.TownyDiagnosticsService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

public final class TownyBootstrapListener implements Listener {

    private final Plugin plugin;
    private final EconomyService economyService;
    private final TownyDiagnosticsService townyDiagnosticsService;
    private final EnhancedLogger operationsLog;
    private boolean townyLifecycleRegistered;

    public TownyBootstrapListener(
            Plugin plugin,
            EconomyService economyService,
            TownyDiagnosticsService townyDiagnosticsService,
            EnhancedLogger operationsLog
    ) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.townyDiagnosticsService = townyDiagnosticsService;
        this.operationsLog = operationsLog;
    }

    public void registerIfTownyEnabled() {
        if (townyLifecycleRegistered) {
            return;
        }
        PluginManager pluginManager = plugin.getServer().getPluginManager();
        if (!pluginManager.isPluginEnabled("Towny")) {
            return;
        }
        pluginManager.registerEvents(new TownyLifecycleListener(economyService, operationsLog), plugin);
        townyDiagnosticsService.setBackend(new TownyDiagnosticsBackendImpl(economyService, operationsLog));
        townyLifecycleRegistered = true;
        operationsLog.info("Registered Towny lifecycle listener and diagnostics backend.");
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (!"Towny".equals(event.getPlugin().getName())) {
            return;
        }
        registerIfTownyEnabled();
    }
}
