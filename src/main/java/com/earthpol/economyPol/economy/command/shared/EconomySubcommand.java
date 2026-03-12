package com.earthpol.economyPol.economy.command.shared;

import org.bukkit.command.CommandSender;

import java.util.List;

public interface EconomySubcommand {

    String name();

    String usage();

    boolean execute(CommandSender sender, String[] args);

    default List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
