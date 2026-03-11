package com.earthpol.economyPol.command.admin;

import com.earthpol.economyPol.command.shared.AbstractEconomySubcommand;
import com.earthpol.economyPol.command.shared.CommandDependencies;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.List;

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
        return "/economypol admin balance <player>";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(usage());
            return true;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
        sendBalanceView(sender, target);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return onlinePlayerNames();
        }
        return List.of();
    }
}
