package com.earthpol.economyPol.command.player;

import com.earthpol.economyPol.command.shared.AbstractEconomySubcommand;
import com.earthpol.economyPol.command.shared.CommandDependencies;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class NormalizeWalletSubcommand extends AbstractEconomySubcommand {

    public NormalizeWalletSubcommand(CommandDependencies dependencies) {
        super(dependencies);
    }

    @Override
    public String name() {
        return "normalizewallet";
    }

    @Override
    public String usage() {
        return "/economypol normalizewallet";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length != 0) {
            sender.sendMessage(usage());
            return true;
        }
        Player player = requirePlayer(sender, "Only players can normalize their ender wallet.");
        if (player == null) {
            return true;
        }
        dependencies.enderWalletService().normalizeOnlineEnderWallet(player);
        sender.sendMessage("Ender wallet normalized.");
        return true;
    }
}
