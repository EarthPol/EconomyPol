package com.earthpol.economyPol.economy.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

public final class HelpCommand {

    private static final List<HelpEntry> PLAYER_COMMANDS = List.of(
            new HelpEntry("/bal", "View your balance breakdown.", null),
            new HelpEntry("/baltop", "Show top player balances.", null),
            new HelpEntry("/claim", "Claim as much overflow money as fits in your inventory.", null),
            new HelpEntry(
                    "/economypol paymentdelivery <default | skip_inventory | skip_inventory_and_enderchest>",
                    "Choose how incoming money and returned change are routed. Default order: Inventory -> Enderchest -> Overflow account",
                    null
            ),
            new HelpEntry("/compress", "Automatically compress ender chest currency to the largest denomination(s).", null),
            new HelpEntry("/economypol help", "Show this help.", null)
    );

    private static final List<HelpEntry> ADMIN_COMMANDS = List.of(
            new HelpEntry("/economypol deposit <amount|all>", "Deposit physical money into the overflow account.", null),
            new HelpEntry("/economypol withdraw <amount>", "Withdraw a specific overflow amount into inventory.", null),
            new HelpEntry("/economypol admin balance <player>", "View detailed player balance information and UUID.", null),
            new HelpEntry("/economypol admin check <report>", "Run a database health check report.", null),
            new HelpEntry("/economypol admin cleanup towny-orphans", "Remove orphaned Towny accounts.", null),
            new HelpEntry("/economypol admin reload", "Reload runtime settings from config.yml.", null)
    );

    public void sendHelp(CommandSender sender, boolean adminAccess) {
        sender.sendMessage(buildSection("Commands", PLAYER_COMMANDS, true));
        if (adminAccess) {
            sender.sendMessage(buildSection("Admin Commands", ADMIN_COMMANDS, false));
        }
    }

    private Component buildSection(String title, List<HelpEntry> entries, boolean includeAliasNote) {
        List<Component> lines = new ArrayList<>();
        lines.add(
                Component.text("[", NamedTextColor.DARK_GRAY)
                        .append(Component.text("EconomyPol", NamedTextColor.GOLD))
                        .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(title, NamedTextColor.YELLOW))
        );
        for (HelpEntry entry : entries) {
            Component line = Component.text(" - ", NamedTextColor.DARK_GRAY)
                    .append(Component.text(entry.usage(), NamedTextColor.AQUA))
                    .append(Component.text(" - ", NamedTextColor.DARK_GRAY))
                    .append(Component.text(entry.description(), NamedTextColor.WHITE));
            if (entry.aliasUsage() != null) {
                line = line.append(Component.text(" Alias: ", NamedTextColor.GRAY))
                        .append(Component.text(entry.aliasUsage(), NamedTextColor.YELLOW));
            }
            lines.add(line);
        }
        if (includeAliasNote) {
            lines.add(
                    Component.text(" Alias: ", NamedTextColor.GRAY)
                            .append(Component.text("/ecopol", NamedTextColor.YELLOW))
                            .append(Component.text(" works in place of ", NamedTextColor.WHITE))
                            .append(Component.text("/economypol", NamedTextColor.AQUA))
                            .append(Component.text(".", NamedTextColor.WHITE))
            );
        }
        return Component.join(JoinConfiguration.separator(Component.newline()), lines);
    }

    private record HelpEntry(String usage, String description, String aliasUsage) {
    }
}
