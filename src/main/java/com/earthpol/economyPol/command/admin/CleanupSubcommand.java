package com.earthpol.economyPol.command.admin;

import com.earthpol.economyPol.command.shared.AbstractEconomySubcommand;
import com.earthpol.economyPol.command.shared.CommandDependencies;
import com.earthpol.economyPol.service.TownyCleanupResult;
import org.bukkit.command.CommandSender;

import java.util.List;

public final class CleanupSubcommand extends AbstractEconomySubcommand {

    private static final String TOWNY_ORPHANS_TARGET = "towny-orphans";

    public CleanupSubcommand(CommandDependencies dependencies) {
        super(dependencies);
    }

    @Override
    public String name() {
        return "cleanup";
    }

    @Override
    public String usage() {
        return "/economypol admin cleanup <" + TOWNY_ORPHANS_TARGET + ">";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length != 1 || !TOWNY_ORPHANS_TARGET.equalsIgnoreCase(args[0])) {
            sender.sendMessage(usage());
            return true;
        }

        TownyCleanupResult result = dependencies.townyDiagnosticsService().cleanupOrphanedAccounts();
        dependencies.healthcheckLogger().info(result.toString());
        for (String line : result.toChatLines()) {
            sender.sendMessage(line);
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of(TOWNY_ORPHANS_TARGET);
        }
        return List.of();
    }
}
