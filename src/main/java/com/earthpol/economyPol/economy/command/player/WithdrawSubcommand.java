package com.earthpol.economyPol.economy.command.player;

import com.earthpol.economyPol.economy.command.shared.AbstractEconomySubcommand;
import com.earthpol.economyPol.economy.command.shared.CommandDependencies;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class WithdrawSubcommand extends AbstractEconomySubcommand {

    private static final List<MoneyRouteTarget> INVENTORY_ONLY_ROUTING = List.of(MoneyRouteTarget.INVENTORY);

    public WithdrawSubcommand(CommandDependencies dependencies) {
        super(dependencies);
    }

    @Override
    public String name() {
        return "withdraw";
    }

    @Override
    public String usage() {
        return "/economypol withdraw [amount]";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "Only players can self-withdraw.");
        if (player == null) {
            return true;
        }
        boolean admin = player.hasPermission("economypol.admin");
        if (args.length > 1) {
            sender.sendMessage(usage());
            return true;
        }

        if (!admin) {
            if (args.length == 0 || isAllAlias(args[0])) {
                var result = dependencies.economyService().withdrawMaxCustodialToInventory(player);
                sendOverflowClaimResult(player, result);
                return true;
            }
            sender.sendMessage("Specific withdrawal amounts require economypol.admin. Use /economypol withdraw to physicalize as much money as fits in your inventory.");
            return true;
        }

        if (args.length == 0 || isAllAlias(args[0])) {
            var result = dependencies.economyService().withdrawMaxCustodialToInventory(player);
            sendOverflowClaimResult(player, result);
            return true;
        }

        long amount = parseAmount(args[0]);
        if (amount < 0L) {
            sender.sendMessage(usage());
            return true;
        }
        var result = dependencies.economyService().withdrawCustodialAsPhysicalMoney(
                player,
                amount,
                INVENTORY_ONLY_ROUTING
        );
        sender.sendMessage(result.message() + " Delivered: " +
                dependencies.economyService().denominationService().format(result.processedAmount()) +
                ", retained: " +
                dependencies.economyService().denominationService().format(result.remainder()));
        return true;
    }

    private boolean isAllAlias(String raw) {
        return "all".equalsIgnoreCase(raw) || "max".equalsIgnoreCase(raw);
    }
}
