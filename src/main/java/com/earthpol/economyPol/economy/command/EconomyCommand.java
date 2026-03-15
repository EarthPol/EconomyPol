package com.earthpol.economyPol.economy.command;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.command.admin.CheckSubcommand;
import com.earthpol.economyPol.economy.command.admin.CleanupSubcommand;
import com.earthpol.economyPol.economy.command.admin.ReloadSubcommand;
import com.earthpol.economyPol.economy.command.shared.CommandDependencies;
import com.earthpol.economyPol.economy.command.shared.EconomySubcommand;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.command.player.*;
import com.earthpol.economyPol.economy.service.databasecheck.DatabaseCheckService;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.player.EnderWalletService;
import com.earthpol.economyPol.towny.TownyService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

public final class EconomyCommand implements TabExecutor {

    private static final String BALANCE_COMMAND = "balance";
    private static final String BALANCE_ALIAS = "bal";
    private static final String BALANCE_TOP_COMMAND = "balancetop";
    private static final String BALANCE_TOP_ALIAS = "baltop";
    private static final String CLAIM_COMMAND = "claim";
    private static final String NORMALIZE_WALLET_COMMAND = "normalizewallet";
    private static final String NORMALIZE_WALLET_ALIAS = "compress";
    private static final String HELP_COMMAND = "help";

    private final Map<String, EconomySubcommand> playerCommands;
    private final Map<String, EconomySubcommand> adminCommands;
    private final HelpCommand helpCommand;

    public EconomyCommand(
            EconomyService economyService,
            EnderWalletService enderWalletService,
            DatabaseCheckService databaseCheckService,
            TownyService townyService,
            PluginSettings settings,
            EnhancedLogger logger,
            EnhancedLogger healthcheckLogger
    ) {
        CommandDependencies dependencies = new CommandDependencies(
                economyService,
                enderWalletService,
                databaseCheckService,
                townyService,
                settings,
                logger,
                healthcheckLogger
        );
        this.playerCommands = registerPlayerCommands(dependencies);
        this.adminCommands = registerAdminCommands(dependencies);
        this.helpCommand = new HelpCommand();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (isDirectBalanceCommand(command, label)) {
            EconomySubcommand balance = playerCommands.get(BALANCE_COMMAND);
            return balance != null && balance.execute(sender, args);
        }
        if (isDirectBalanceTopCommand(command, label)) {
            EconomySubcommand balanceTop = playerCommands.get(BALANCE_TOP_COMMAND);
            return balanceTop != null && balanceTop.execute(sender, args);
        }
        if (isDirectClaimCommand(command, label)) {
            EconomySubcommand claim = playerCommands.get(CLAIM_COMMAND);
            return claim != null && claim.execute(sender, args);
        }
        if (isDirectNormalizeWalletCommand(command, label)) {
            EconomySubcommand normalizeWallet = playerCommands.get(NORMALIZE_WALLET_COMMAND);
            return normalizeWallet != null && normalizeWallet.execute(sender, args);
        }

        if (args.length == 0) {
            sendRootUsage(sender);
            return true;
        }
        if (HELP_COMMAND.equalsIgnoreCase(args[0])) {
            sendRootUsage(sender);
            return true;
        }

        if ("admin".equalsIgnoreCase(args[0])) {
            return handleAdminCommand(sender, args);
        }

        EconomySubcommand subcommand = playerCommands.get(args[0].toLowerCase(Locale.ROOT));
        if (subcommand == null) {
            sendRootUsage(sender);
            return true;
        }
        return subcommand.execute(sender, slice(args, 1));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (isDirectBalanceCommand(command, alias)) {
            EconomySubcommand balance = playerCommands.get(BALANCE_COMMAND);
            if (balance == null) {
                return List.of();
            }
            return balance.tabComplete(sender, args);
        }
        if (isDirectBalanceTopCommand(command, alias)) {
            EconomySubcommand balanceTop = playerCommands.get(BALANCE_TOP_COMMAND);
            if (balanceTop == null) {
                return List.of();
            }
            return balanceTop.tabComplete(sender, args);
        }
        if (isDirectClaimCommand(command, alias)) {
            EconomySubcommand claim = playerCommands.get(CLAIM_COMMAND);
            if (claim == null) {
                return List.of();
            }
            return claim.tabComplete(sender, args);
        }
        if (isDirectNormalizeWalletCommand(command, alias)) {
            EconomySubcommand normalizeWallet = playerCommands.get(NORMALIZE_WALLET_COMMAND);
            if (normalizeWallet == null) {
                return List.of();
            }
            return normalizeWallet.tabComplete(sender, args);
        }

        if (args.length == 1) {
            Stream<String> rootCommands = playerCommands.keySet().stream();
            if (hasAdminAccess(sender)) {
                rootCommands = Stream.concat(rootCommands, Stream.of("admin"));
            }
            return Stream.concat(rootCommands, Stream.of(HELP_COMMAND)).sorted().toList();
        }

        if ("admin".equalsIgnoreCase(args[0])) {
            if (!hasAdminAccess(sender)) {
                return List.of();
            }
            if (args.length == 2) {
                return adminCommands.keySet().stream().sorted().toList();
            }
            EconomySubcommand adminSubcommand = adminCommands.get(args[1].toLowerCase(Locale.ROOT));
            if (adminSubcommand == null) {
                return List.of();
            }
            return adminSubcommand.tabComplete(sender, slice(args, 2));
        }

        EconomySubcommand playerSubcommand = playerCommands.get(args[0].toLowerCase(Locale.ROOT));
        if (playerSubcommand == null) {
            return List.of();
        }
        return playerSubcommand.tabComplete(sender, slice(args, 1));
    }

    private boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (!hasAdminAccess(sender)) {
            sender.sendMessage("You do not have permission to run admin commands.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("/economypol admin <" + String.join("|", adminCommands.keySet()) + ">");
            return true;
        }
        EconomySubcommand subcommand = adminCommands.get(args[1].toLowerCase(Locale.ROOT));
        if (subcommand == null) {
            sender.sendMessage("/economypol admin <" + String.join("|", adminCommands.keySet()) + ">");
            return true;
        }
        return subcommand.execute(sender, slice(args, 2));
    }

    private void sendRootUsage(CommandSender sender) {
        helpCommand.sendHelp(sender, hasAdminAccess(sender));
    }

    private boolean hasAdminAccess(CommandSender sender) {
        return !(sender instanceof Player) || sender.hasPermission("economypol.admin");
    }

    private static String[] slice(String[] values, int fromIndex) {
        if (fromIndex >= values.length) {
            return new String[0];
        }
        String[] slice = new String[values.length - fromIndex];
        System.arraycopy(values, fromIndex, slice, 0, slice.length);
        return slice;
    }

    private static Map<String, EconomySubcommand> registerPlayerCommands(CommandDependencies dependencies) {
        Map<String, EconomySubcommand> commands = new LinkedHashMap<>();
        BalanceSubcommand balanceSubcommand = new BalanceSubcommand(dependencies);
        register(commands, balanceSubcommand);
        commands.put(BALANCE_ALIAS, balanceSubcommand);
        BalanceTopSubCommand balanceTopSubcommand =
                new BalanceTopSubCommand(dependencies);
        register(commands, balanceTopSubcommand);
        commands.put(BALANCE_TOP_ALIAS, balanceTopSubcommand);
        register(commands, new DepositSubcommand(dependencies));
        register(commands, new WithdrawSubcommand(dependencies));
        register(commands, new ClaimSubcommand(dependencies));
        register(commands, new PaymentDeliverySubcommand(dependencies));
        NormalizeWalletSubcommand normalizeWalletSubcommand = new NormalizeWalletSubcommand(dependencies);
        register(commands, normalizeWalletSubcommand);
        commands.put(NORMALIZE_WALLET_ALIAS, normalizeWalletSubcommand);
        return Collections.unmodifiableMap(new LinkedHashMap<>(commands));
    }

    private static Map<String, EconomySubcommand> registerAdminCommands(CommandDependencies dependencies) {
        Map<String, EconomySubcommand> commands = new LinkedHashMap<>();
        register(commands, new com.earthpol.economyPol.economy.command.admin.BalanceSubcommand(dependencies));
        register(commands, new CheckSubcommand(dependencies));
        register(commands, new CleanupSubcommand(dependencies));
        register(commands, new ReloadSubcommand(dependencies));
        return Collections.unmodifiableMap(new LinkedHashMap<>(commands));
    }

    private static void register(Map<String, EconomySubcommand> commands, EconomySubcommand subcommand) {
        commands.put(subcommand.name(), subcommand);
    }

    private static boolean isDirectBalanceTopCommand(Command command, String label) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        String normalizedLabel = label == null ? "" : label.toLowerCase(Locale.ROOT);
        return BALANCE_TOP_ALIAS.equals(commandName) || BALANCE_TOP_ALIAS.equals(normalizedLabel);
    }

    private static boolean isDirectBalanceCommand(Command command, String label) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        String normalizedLabel = label == null ? "" : label.toLowerCase(Locale.ROOT);
        return BALANCE_ALIAS.equals(commandName) || BALANCE_ALIAS.equals(normalizedLabel);
    }

    private static boolean isDirectClaimCommand(Command command, String label) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        String normalizedLabel = label == null ? "" : label.toLowerCase(Locale.ROOT);
        return CLAIM_COMMAND.equals(commandName) || CLAIM_COMMAND.equals(normalizedLabel);
    }

    private static boolean isDirectNormalizeWalletCommand(Command command, String label) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        String normalizedLabel = label == null ? "" : label.toLowerCase(Locale.ROOT);
        return NORMALIZE_WALLET_ALIAS.equals(commandName) || NORMALIZE_WALLET_ALIAS.equals(normalizedLabel);
    }
}

