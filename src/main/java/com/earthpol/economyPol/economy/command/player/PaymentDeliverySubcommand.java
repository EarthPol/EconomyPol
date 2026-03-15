package com.earthpol.economyPol.economy.command.player;

import com.earthpol.economyPol.economy.command.shared.AbstractEconomySubcommand;
import com.earthpol.economyPol.economy.command.shared.CommandDependencies;
import com.earthpol.economyPol.economy.model.IncomingPaymentDeliveryPreference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;

public final class PaymentDeliverySubcommand extends AbstractEconomySubcommand {

    public PaymentDeliverySubcommand(CommandDependencies dependencies) {
        super(dependencies);
    }

    @Override
    public String name() {
        return "paymentdelivery";
    }

    @Override
    public String usage() {
        return "/economypol paymentdelivery <default|skip_inventory|skip_inventory_and_enderchest>";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "Only players can change incoming payment delivery preferences.");
        if (player == null) {
            return true;
        }
        if (args.length > 1) {
            sender.sendMessage(usage());
            return true;
        }

        if (args.length == 0) {
            IncomingPaymentDeliveryPreference current =
                    dependencies.economyService().getIncomingPaymentDeliveryPreference(player);
            player.sendMessage(
                    Component.text("[", NamedTextColor.DARK_GRAY)
                            .append(Component.text("EconomyPol", NamedTextColor.GOLD))
                            .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                            .append(Component.text("Current incoming payment delivery preference: ", NamedTextColor.GRAY))
                            .append(Component.text(current.commandToken(), NamedTextColor.YELLOW))
            );
            player.sendMessage(
                    Component.text("Default order: ", NamedTextColor.GRAY)
                            .append(Component.text(current.description(), NamedTextColor.WHITE))
            );
            player.sendMessage(
                    Component.text("Usage: ", NamedTextColor.GRAY)
                            .append(Component.text(usage(), NamedTextColor.AQUA))
            );
            return true;
        }

        IncomingPaymentDeliveryPreference preference;
        try {
            preference = IncomingPaymentDeliveryPreference.fromCommandToken(args[0]);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(usage());
            return true;
        }

        dependencies.economyService().setIncomingPaymentDeliveryPreference(player, preference);
        sender.sendMessage("Incoming payment delivery preference set to " + preference.commandToken() + ".");
        sender.sendMessage("Passive incoming money and returned change will route as: " + preference.description());
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return Arrays.stream(IncomingPaymentDeliveryPreference.values())
                    .map(IncomingPaymentDeliveryPreference::commandToken)
                    .toList();
        }
        return List.of();
    }
}
