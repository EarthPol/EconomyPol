package com.earthpol.economyPol.economy.command.player;

import com.earthpol.economyPol.economy.command.shared.AbstractEconomySubcommand;
import com.earthpol.economyPol.economy.command.shared.CommandDependencies;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class SkipShulkerSubcommand extends AbstractEconomySubcommand {

    public SkipShulkerSubcommand(CommandDependencies dependencies) {
        super(dependencies);
    }

    @Override
    public String name() {
        return "skip_shulker";
    }

    @Override
    public String usage() {
        return "/economypol skip_shulker <true|false>";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = requirePlayer(sender, "Only players can change shulker delivery preferences.");
        if (player == null) {
            return true;
        }
        if (args.length > 1) {
            sender.sendMessage(usage());
            return true;
        }

        if (args.length == 0) {
            boolean current = dependencies.economyService().getSkipShulkerDelivery(player);
            player.sendMessage(
                    Component.text("[", NamedTextColor.DARK_GRAY)
                            .append(Component.text("EconomyPol", NamedTextColor.GOLD))
                            .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                            .append(Component.text("Current skip_shulker setting: ", NamedTextColor.GRAY))
                            .append(Component.text(Boolean.toString(current), NamedTextColor.YELLOW))
            );
            player.sendMessage(
                    Component.text("When false, automatic incoming money can fill top-level yellow shulkers first. "
                                    + "When true, automatic shulker delivery is skipped.", NamedTextColor.WHITE)
            );
            player.sendMessage(
                    Component.text("Usage: ", NamedTextColor.GRAY)
                            .append(Component.text(usage(), NamedTextColor.AQUA))
            );
            return true;
        }

        Boolean skipShulker = parseBoolean(args[0]);
        if (skipShulker == null) {
            sender.sendMessage(usage());
            return true;
        }

        dependencies.economyService().setSkipShulkerDelivery(player, skipShulker);
        sender.sendMessage("skip_shulker set to " + skipShulker + ".");
        if (skipShulker) {
            sender.sendMessage("Automatic incoming money and returned change will skip yellow shulkers.");
        } else {
            sender.sendMessage("Automatic incoming money and returned change can flow into yellow shulkers first.");
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("true", "false");
        }
        return List.of();
    }

    private Boolean parseBoolean(String raw) {
        if ("true".equalsIgnoreCase(raw)) {
            return true;
        }
        if ("false".equalsIgnoreCase(raw)) {
            return false;
        }
        return null;
    }
}
