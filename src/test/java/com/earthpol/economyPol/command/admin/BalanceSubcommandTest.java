package com.earthpol.economyPol.command.admin;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.command.shared.CommandDependencies;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.PlayerBalanceView;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.databasecheck.DatabaseCheckService;
import com.earthpol.economyPol.economy.service.player.EnderWalletService;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import com.earthpol.economyPol.towny.TownyService;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class BalanceSubcommandTest {

    @Test
    void adminBalanceShowsDetailedViewIncludingUuid() {
        MockBukkit.mock();
        try {
            EconomyService economyService = mock(EconomyService.class);
            DenominationService denominationService = mock(DenominationService.class);
            CommandSender sender = mock(CommandSender.class);
            String playerName = "Alice";
            OfflinePlayer target = MockBukkit.getMock().getOfflinePlayer(playerName);
            UUID playerUuid = target.getUniqueId();

            when(economyService.balanceView(argThat(player -> player != null && playerUuid.equals(player.getUniqueId()))))
                    .thenReturn(new PlayerBalanceView(40L, 5L, 90L, 10L, true));
            when(economyService.denominationService()).thenReturn(denominationService);
            when(denominationService.format(100L)).thenReturn("100 Gold Coins");
            when(denominationService.format(40L)).thenReturn("40 Gold Coins");
            when(denominationService.format(5L)).thenReturn("5 Gold Coins");
            when(denominationService.format(90L)).thenReturn("90 Gold Coins");
            when(denominationService.format(10L)).thenReturn("10 Gold Coins");

            com.earthpol.economyPol.economy.command.admin.BalanceSubcommand subcommand =
                    new com.earthpol.economyPol.economy.command.admin.BalanceSubcommand(dependencies(economyService));

            subcommand.execute(sender, new String[] {playerName});

            verify(sender).sendMessage("Balance for " + playerName + ":");
            verify(sender).sendMessage("  UUID on file: " + playerUuid);
            verify(sender).sendMessage("  Spendable: 100 Gold Coins");
            verify(sender).sendMessage("  Custodial available: 40 Gold Coins");
            verify(sender).sendMessage("  Custodial reserved: 5 Gold Coins");
            verify(sender).sendMessage("  Live money: 90 Gold Coins");
            verify(sender).sendMessage("  Frozen ender wallet: 10 Gold Coins");
            verify(sender).sendMessage("  Locked: true");
        } finally {
            MockBukkit.unmock();
        }
    }

    private CommandDependencies dependencies(EconomyService economyService) {
        return new CommandDependencies(
                economyService,
                mock(EnderWalletService.class),
                mock(DatabaseCheckService.class),
                mock(TownyService.class),
                mock(PluginSettings.class),
                mock(EnhancedLogger.class),
                mock(EnhancedLogger.class)
        );
    }
}
