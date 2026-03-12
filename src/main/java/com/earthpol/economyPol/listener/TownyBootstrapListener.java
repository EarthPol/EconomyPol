package com.earthpol.economyPol.listener;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.service.EconomyService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

public final class TownyBootstrapListener implements Listener {

    private final Plugin plugin;
    private final EconomyService economyService;
    private final EnhancedLogger operationsLog;
    private boolean townyLifecycleRegistered;

    public TownyBootstrapListener(Plugin plugin, EconomyService economyService, EnhancedLogger operationsLog) {
        this.plugin = plugin;
        this.economyService = economyService;
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
        townyLifecycleRegistered = true;
        operationsLog.info("Registered Towny lifecycle listener.");
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (!"Towny".equals(event.getPlugin().getName())) {
            return;
        }
        registerIfTownyEnabled();
    }
}
