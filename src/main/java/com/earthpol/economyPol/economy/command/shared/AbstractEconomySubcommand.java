package com.earthpol.economyPol.economy.command.shared;

import com.earthpol.economyPol.economy.model.PlayerBalanceView;
import com.earthpol.economyPol.economy.model.MoneyOperationFailureReason;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
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

    protected void sendBalanceView(CommandSender sender, Player player) {
        try {
            PlayerBalanceView view = dependencies.economyService().balanceView(player);
            String spendable = dependencies.economyService().denominationService().format(view.liveMoney());
            String inventory = dependencies.economyService().denominationService().format(view.inventoryMoney());
            String enderChest = dependencies.economyService().denominationService().format(view.enderChestMoney());
            String overflow = dependencies.economyService().denominationService().format(view.custodialAvailable());
            player.sendMessage(Component.join(
                    JoinConfiguration.separator(Component.newline()),
                    List.of(
                            Component.text("[", NamedTextColor.DARK_GRAY)
                                    .append(Component.text("EconomyPol", NamedTextColor.GOLD))
                                    .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                                    .append(Component.text("Your Balance:", NamedTextColor.YELLOW)),
                            Component.text("Spendable: ", NamedTextColor.GRAY)
                                    .append(Component.text(spendable, NamedTextColor.WHITE))
                                    .append(Component.text(" (Inventory + enderchest)", NamedTextColor.DARK_GRAY)),
                            Component.text("Inventory: ", NamedTextColor.GRAY)
                                    .append(Component.text(inventory, NamedTextColor.WHITE)),
                            Component.text("Enderchest: ", NamedTextColor.GRAY)
                                    .append(Component.text(enderChest, NamedTextColor.WHITE)),
                            Component.text("Overflow account: ", NamedTextColor.GRAY)
                                    .append(Component.text(overflow, NamedTextColor.WHITE))
                                    .append(Component.text(" Not spendable. Must withdrawn to spend", NamedTextColor.DARK_GRAY)),
                            Component.text("Claim overflow balance with ", NamedTextColor.AQUA)
                                    .append(Component.text("/claim", NamedTextColor.YELLOW))
                    )
            ));
        } catch (IllegalStateException exception) {
            sender.sendMessage("Player account does not exist.");
        }
    }

    protected void sendBalanceViewAdmin(CommandSender sender, OfflinePlayer target) {
        try {
            PlayerBalanceView view = dependencies.economyService().balanceView(target);
            sender.sendMessage("Balance for " + target.getName() + ":");
            sender.sendMessage("  UUID on file: " + target.getUniqueId());
            sender.sendMessage("  Spendable: " + dependencies.economyService().denominationService().format(view.spendable()));
            sender.sendMessage("  Custodial available: " + dependencies.economyService().denominationService().format(view.custodialAvailable()));
            sender.sendMessage("  Custodial reserved: " + dependencies.economyService().denominationService().format(view.custodialReserved()));
            sender.sendMessage("  Live money: " + dependencies.economyService().denominationService().format(view.liveMoney()));
            sender.sendMessage("  Frozen ender wallet: " + dependencies.economyService().denominationService().format(view.frozenEnderWallet()));
            sender.sendMessage("  Locked: " + view.locked());
        } catch (IllegalStateException exception) {
            sender.sendMessage("Player account does not exist.");
        }
    }

    protected void sendOverflowClaimResult(Player player, MoneyOperationResult result) {
        if (!result.success() || result.processedAmount() <= 0L) {
            if (result.failureReason() == MoneyOperationFailureReason.INSUFFICIENT_FUNDS && result.requestedAmount() == 0L) {
                player.sendMessage(
                        Component.text("[", NamedTextColor.DARK_GRAY)
                                .append(Component.text("EconomyPol", NamedTextColor.GOLD))
                                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                                .append(Component.text("You have no money in your overflow account.", NamedTextColor.YELLOW))
                );
                return;
            }
            player.sendMessage(result.message());
            return;
        }
        String claimedAmount = dependencies.economyService().denominationService().format(result.processedAmount());
        player.sendMessage(
                Component.text("[", NamedTextColor.DARK_GRAY)
                        .append(Component.text("EconomyPol", NamedTextColor.GOLD))
                        .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                        .append(Component.text(claimedAmount, NamedTextColor.WHITE))
                        .append(Component.text(" claimed from overflow account.", NamedTextColor.AQUA))
        );
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
