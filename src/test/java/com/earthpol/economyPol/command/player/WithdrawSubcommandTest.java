package com.earthpol.economyPol.command.player;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.command.player.WithdrawSubcommand;
import com.earthpol.economyPol.economy.command.shared.CommandDependencies;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.databasecheck.DatabaseCheckService;
import com.earthpol.economyPol.economy.service.player.EnderWalletService;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import com.earthpol.economyPol.towny.TownyService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class WithdrawSubcommandTest {

    @Test
    void nonAdminWithoutAmountWithdrawsMaximumThatFits() {
        EconomyService economyService = mock(EconomyService.class);
        DenominationService denominationService = mock(DenominationService.class);
        Player player = mock(Player.class);

        when(player.hasPermission("economypol.admin")).thenReturn(false);
        when(economyService.withdrawMaxCustodialToInventory(player))
                .thenReturn(MoneyOperationResult.success(126L, 126L, 0L, "Withdraw processed."));
        when(economyService.getCustodialAvailable(player)).thenReturn(874L);
        when(economyService.denominationService()).thenReturn(denominationService);
        when(denominationService.format(126L)).thenReturn("126 Gold Coins");
        when(denominationService.format(874L)).thenReturn("874 Gold Coins");

        WithdrawSubcommand subcommand = new WithdrawSubcommand(dependencies(economyService));

        subcommand.execute(player, new String[0]);

        verify(economyService).withdrawMaxCustodialToInventory(player);
        verify(economyService, never()).withdrawCustodialAsPhysicalMoney(player, 126L);
    }

    @Test
    void nonAdminWithSpecificAmountIsRejected() {
        EconomyService economyService = mock(EconomyService.class);
        Player player = mock(Player.class);

        when(player.hasPermission("economypol.admin")).thenReturn(false);

        WithdrawSubcommand subcommand = new WithdrawSubcommand(dependencies(economyService));

        subcommand.execute(player, new String[] {"10"});

        verify(economyService, never()).withdrawMaxCustodialToInventory(player);
        verify(economyService, never()).withdrawCustodialAsPhysicalMoney(player, 10L);
        verify(player).sendMessage(contains("Specific withdrawal amounts require economypol.admin"));
    }

    @Test
    void adminWithSpecificAmountUsesExplicitWithdrawal() {
        EconomyService economyService = mock(EconomyService.class);
        DenominationService denominationService = mock(DenominationService.class);
        Player player = mock(Player.class);

        when(player.hasPermission("economypol.admin")).thenReturn(true);
        when(economyService.withdrawCustodialAsPhysicalMoney(player, 90L))
                .thenReturn(MoneyOperationResult.success(90L, 54L, 36L, "Withdraw processed."));
        when(economyService.denominationService()).thenReturn(denominationService);
        when(denominationService.format(54L)).thenReturn("54 Gold Coins");
        when(denominationService.format(36L)).thenReturn("36 Gold Coins");

        WithdrawSubcommand subcommand = new WithdrawSubcommand(dependencies(economyService));

        subcommand.execute(player, new String[] {"90"});

        verify(economyService).withdrawCustodialAsPhysicalMoney(player, 90L);
        verify(economyService, never()).withdrawMaxCustodialToInventory(player);
        verify(player).sendMessage(eq("Withdraw processed. Delivered: 54 Gold Coins, retained: 36 Gold Coins"));
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
