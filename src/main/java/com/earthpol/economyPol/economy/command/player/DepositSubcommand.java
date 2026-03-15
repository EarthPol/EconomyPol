package com.earthpol.economyPol.economy.command.player;

import com.earthpol.economyPol.economy.command.shared.AbstractEconomySubcommand;
import com.earthpol.economyPol.economy.command.shared.CommandDependencies;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class DepositSubcommand extends AbstractEconomySubcommand {

    public DepositSubcommand(CommandDependencies dependencies) {
        super(dependencies);
    }

    @Override
    public String name() {
        return "deposit";
    }

    @Override
    public String usage() {
        return "/economypol deposit <amount|all>";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "Only players can self-deposit.");
        if (player == null) {
            return true;
        }
        if (!player.hasPermission("economypol.admin")) {
            sender.sendMessage("You do not have permission to self-deposit physical money into custodial.");
            return true;
        }
        if (args.length > 1) {
            sender.sendMessage(usage());
            return true;
        }
        long amount = parseAmount(args.length == 1 ? args[0] : "0");
        var result = dependencies.economyService().depositSelf(player, amount);
        sender.sendMessage(result.message());
        return true;
    }
}
