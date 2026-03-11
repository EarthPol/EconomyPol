package com.earthpol.economyPol.command.player;

import com.earthpol.economyPol.command.shared.AbstractEconomySubcommand;
import com.earthpol.economyPol.command.shared.CommandDependencies;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class WithdrawSubcommand extends AbstractEconomySubcommand {

    public WithdrawSubcommand(CommandDependencies dependencies) {
        super(dependencies);
    }

    @Override
    public String name() {
        return "withdraw";
    }

    @Override
    public String usage() {
        return "/economypol withdraw <amount>";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "Only players can self-withdraw.");
        if (player == null) {
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(usage());
            return true;
        }
        long amount = parseAmount(args[0]);
        var result = dependencies.economyService().withdrawCustodialAsPhysicalMoney(player, amount);
        sender.sendMessage(result.message() + " Delivered: " + result.processedAmount() + ", retained: " + result.remainder());
        return true;
    }
}
