package com.earthpol.economyPol.command.shared;

import com.earthpol.economyPol.domain.PlayerBalanceView;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public abstract class AbstractEconomySubcommand implements EconomySubcommand {

    protected final CommandDependencies dependencies;

    protected AbstractEconomySubcommand(CommandDependencies dependencies) {
        this.dependencies = dependencies;
    }

    protected Player requirePlayer(CommandSender sender, String failureMessage) {
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(failureMessage);
        return null;
    }

    protected void sendBalanceView(CommandSender sender, OfflinePlayer target) {
        PlayerBalanceView view = dependencies.economyService().balanceView(target);
        sender.sendMessage("Balance for " + target.getName() + ":");
        sender.sendMessage("  Spendable: " + dependencies.economyService().denominationService().format(view.spendable()));
        sender.sendMessage("  Custodial available: " + dependencies.economyService().denominationService().format(view.custodialAvailable()));
        sender.sendMessage("  Custodial reserved: " + dependencies.economyService().denominationService().format(view.custodialReserved()));
        sender.sendMessage("  Live money: " + dependencies.economyService().denominationService().format(view.liveMoney()));
        sender.sendMessage("  Frozen ender wallet: " + dependencies.economyService().denominationService().format(view.frozenEnderWallet()));
        sender.sendMessage("  Locked: " + view.locked());
    }

    protected long parseAmount(String raw) {
        if ("all".equalsIgnoreCase(raw)) {
            return 0L;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException exception) {
            dependencies.operationsLogger().warn("Failed to parse amount: " + raw);
            return -1L;
        }
    }

    protected List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName() != null) {
                names.add(player.getName());
            }
        }
        return names;
    }
}
