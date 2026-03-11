package com.earthpol.economyPol.command.player;

import com.earthpol.economyPol.command.shared.AbstractEconomySubcommand;
import com.earthpol.economyPol.command.shared.CommandDependencies;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class BalanceSubcommand extends AbstractEconomySubcommand {

    public BalanceSubcommand(CommandDependencies dependencies) {
        super(dependencies);
    }

    @Override
    public String name() {
        return "balance";
    }

    @Override
    public String usage() {
        return "/economypol balance";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length != 0) {
            sender.sendMessage(usage());
            return true;
        }
        Player player = requirePlayer(sender, "Console must use /economypol admin balance <player>.");
        if (player == null) {
            return true;
        }
        sendBalanceView(sender, player);
        return true;
    }
}
