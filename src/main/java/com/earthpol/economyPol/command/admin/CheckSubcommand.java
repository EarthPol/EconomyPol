package com.earthpol.economyPol.command.admin;

import com.earthpol.economyPol.command.shared.AbstractEconomySubcommand;
import com.earthpol.economyPol.command.shared.CommandDependencies;
import com.earthpol.economyPol.model.DatabaseCheckReport;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

public final class CheckSubcommand extends AbstractEconomySubcommand {

    public CheckSubcommand(CommandDependencies dependencies) {
        super(dependencies);
    }

    @Override
    public String name() {
        return "check";
    }

    @Override
    public String usage() {
        return "/economypol admin check <" + String.join("|", dependencies.databaseCheckService().availableReports()) + ">";
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(usage());
            return true;
        }

        DatabaseCheckReport report = dependencies.databaseCheckService().runReport(args[0]);
        dependencies.healthcheckLogger().info(report.toString());

        int findingLimit = sender instanceof Player ? 15 : report.findings().size();
        sender.sendMessage(report.toChatMessage(findingLimit));
        if (report.findings().size() > findingLimit) {
            sender.sendMessage("Full report logged to healthcheck.log");
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return dependencies.databaseCheckService().availableReports();
        }
        return List.of();
    }
}
