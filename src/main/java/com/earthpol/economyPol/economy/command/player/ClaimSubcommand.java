package com.earthpol.economyPol.economy.command.player;

import com.earthpol.economyPol.economy.command.shared.AbstractEconomySubcommand;
import com.earthpol.economyPol.economy.command.shared.CommandDependencies;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class ClaimSubcommand extends AbstractEconomySubcommand {

    public ClaimSubcommand(CommandDependencies dependencies) {
        super(dependencies);
    }

    @Override
    public String name() {
        return "claim";
    }

    @Override
    public String usage() {
        return "/claim";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "Only players can claim overflow money.");
        if (player == null) {
            return true;
        }
        if (args.length != 0) {
            sender.sendMessage(usage());
            return true;
        }
        sendOverflowClaimResult(player, dependencies.economyService().withdrawMaxCustodialToInventory(player));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
