package com.earthpol.economyPol.economy.command.admin;

import com.earthpol.economyPol.economy.command.shared.AbstractEconomySubcommand;
import com.earthpol.economyPol.economy.command.shared.CommandDependencies;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.config.RuntimeConfigReloadResult;
import com.earthpol.economyPol.economy.logging.EconomyLoggers.LogType;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class ReloadSubcommand extends AbstractEconomySubcommand {

    public ReloadSubcommand(CommandDependencies dependencies) {
        super(dependencies);
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String usage() {
        return "/economypol admin reload";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length != 0) {
            sender.sendMessage(usage());
            return true;
        }

        RuntimeConfigReloadResult result = dependencies.settings().reloadRuntimeConfig();
        if (!result.success()) {
            sender.sendMessage(result.message());
            return true;
        }

        PluginSettings.LoggingSettings loggingSettings = dependencies.settings().logging();
        dependencies.loggers().applyRetentionPolicy(loggingSettings.retentionPolicy());
        dependencies.loggers().applyConsoleLogging(loggingSettings.consoleEnabled());

        dependencies.loggers().log("Reloaded EconomyPol runtime configuration from config.yml.", LogType.OPERATIONS);
        sender.sendMessage(result.message());
        for (String warning : result.warnings()) {
            sender.sendMessage("Warning: " + warning);
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
