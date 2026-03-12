package com.earthpol.economyPol.command.player;

import com.earthpol.economyPol.command.shared.AbstractEconomySubcommand;
import com.earthpol.economyPol.command.shared.CommandDependencies;
import org.bukkit.command.CommandSender;

public final class BalanceTopSubCommand extends AbstractEconomySubcommand {

    private static final int TOP_COUNT = 10;
    private final BalanceTopCache cache;

    public BalanceTopSubCommand(CommandDependencies dependencies) {
        super(dependencies);
        this.cache = new BalanceTopCache(dependencies, TOP_COUNT);
    }

    @Override
    public String name() {
        return "balancetop";
    }

    @Override
    public String usage() {
        return "/economypol balancetop";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length != 0) {
            sender.sendMessage(usage());
            return true;
        }
        cache.request(sender);
        return true;
    }

}
