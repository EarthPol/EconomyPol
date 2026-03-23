package com.earthpol.economyPol.towny.listener;

import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.logging.EconomyLoggers.LogType;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.towny.TownyIntegrationBackend;
import com.earthpol.economyPol.towny.TownyService;
import com.earthpol.economyPol.towny.repository.TownyGovernmentRepository;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

public final class TownyBootstrapListener implements Listener {

    private final Plugin plugin;
    private final TownyService townyService;
    private final EconomyService economyService;
    private final TownyGovernmentRepository townyGovernmentRepository;
    private final EconomyLoggers loggers;
    private boolean townyLifecycleRegistered;

    public TownyBootstrapListener(
            Plugin plugin,
            TownyService townyService,
            EconomyService economyService,
            TownyGovernmentRepository townyGovernmentRepository,
            EconomyLoggers loggers
    ) {
        this.plugin = plugin;
        this.townyService = townyService;
        this.economyService = economyService;
        this.townyGovernmentRepository = townyGovernmentRepository;
        this.loggers = loggers;
    }

    public void registerIfTownyEnabled() {
        if (townyLifecycleRegistered) {
            return;
        }
        PluginManager pluginManager = plugin.getServer().getPluginManager();
        if (!pluginManager.isPluginEnabled("Towny")) {
            return;
        }
        townyService.activate(new TownyIntegrationBackend(economyService, townyGovernmentRepository, loggers));
        townyService.synchronizeAllGovernments();
        pluginManager.registerEvents(new TownyLifecycleListener(townyService), plugin);
        townyLifecycleRegistered = true;
        loggers.log("Registered Towny lifecycle listener and synchronized Towny government bindings.", LogType.OPERATIONS);
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (!"Towny".equals(event.getPlugin().getName())) {
            return;
        }
        registerIfTownyEnabled();
    }
}
