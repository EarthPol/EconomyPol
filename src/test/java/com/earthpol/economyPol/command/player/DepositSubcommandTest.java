package com.earthpol.economyPol.command.player;

import com.earthpol.economyPol.economy.command.player.DepositSubcommand;
import com.earthpol.economyPol.economy.command.shared.CommandDependencies;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.databasecheck.DatabaseCheckService;
import com.earthpol.economyPol.economy.service.player.EnderWalletService;
import com.earthpol.economyPol.towny.TownyService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class DepositSubcommandTest {

    @Test
    void nonAdminDepositIsRejected() {
        EconomyService economyService = mock(EconomyService.class);
        Player player = mock(Player.class);

        when(player.hasPermission("economypol.admin")).thenReturn(false);

        DepositSubcommand subcommand = new DepositSubcommand(dependencies(economyService));

        subcommand.execute(player, new String[] {"10"});

        verify(economyService, never()).depositSelf(player, 10L);
        verify(player).sendMessage(contains("do not have permission"));
    }

    @Test
    void adminDepositDelegatesToEconomyService() {
        EconomyService economyService = mock(EconomyService.class);
        Player player = mock(Player.class);

        when(player.hasPermission("economypol.admin")).thenReturn(true);
        when(economyService.depositSelf(player, 10L))
                .thenReturn(MoneyOperationResult.success(10L, 10L, 0L, "Funds deposited."));

        DepositSubcommand subcommand = new DepositSubcommand(dependencies(economyService));

        subcommand.execute(player, new String[] {"10"});

        verify(economyService).depositSelf(player, 10L);
        verify(player).sendMessage("Funds deposited.");
    }

    private CommandDependencies dependencies(EconomyService economyService) {
        return new CommandDependencies(
                economyService,
                mock(EnderWalletService.class),
                mock(DatabaseCheckService.class),
                mock(TownyService.class),
                mock(PluginSettings.class),
                mock(EconomyLoggers.class)
        );
    }
}
