package com.earthpol.economyPol.command.player;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.command.player.WithdrawSubcommand;
import com.earthpol.economyPol.economy.command.shared.CommandDependencies;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.databasecheck.DatabaseCheckService;
import com.earthpol.economyPol.economy.service.player.EnderWalletService;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import com.earthpol.economyPol.towny.TownyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class WithdrawSubcommandTest {

    private static final List<MoneyRouteTarget> INVENTORY_ONLY_ROUTING = List.of(MoneyRouteTarget.INVENTORY);

    @Test
    void nonAdminWithoutAmountWithdrawsMaximumThatFits() {
        EconomyService economyService = mock(EconomyService.class);
        DenominationService denominationService = mock(DenominationService.class);
        Player player = mock(Player.class);

        when(player.hasPermission("economypol.admin")).thenReturn(false);
        when(economyService.withdrawMaxCustodialToInventory(player))
                .thenReturn(MoneyOperationResult.success(126L, 126L, 0L, "Withdraw processed."));
        when(economyService.denominationService()).thenReturn(denominationService);
        when(denominationService.format(126L)).thenReturn("126 Gold Coins");

        WithdrawSubcommand subcommand = new WithdrawSubcommand(dependencies(economyService));

        subcommand.execute(player, new String[0]);

        verify(economyService).withdrawMaxCustodialToInventory(player);
        verify(economyService, never()).withdrawCustodialAsPhysicalMoney(player, 126L, INVENTORY_ONLY_ROUTING);
        assertTrue(capturePlainText(player).contains("126 Gold Coins claimed from overflow account."));
    }

    @Test
    void nonAdminWithSpecificAmountIsRejected() {
        EconomyService economyService = mock(EconomyService.class);
        Player player = mock(Player.class);

        when(player.hasPermission("economypol.admin")).thenReturn(false);

        WithdrawSubcommand subcommand = new WithdrawSubcommand(dependencies(economyService));

        subcommand.execute(player, new String[] {"10"});

        verify(economyService, never()).withdrawMaxCustodialToInventory(player);
        verify(economyService, never()).withdrawCustodialAsPhysicalMoney(player, 10L, INVENTORY_ONLY_ROUTING);
        verify(player).sendMessage(contains("Specific withdrawal amounts require economypol.admin"));
    }

    @Test
    void adminWithSpecificAmountUsesExplicitWithdrawal() {
        EconomyService economyService = mock(EconomyService.class);
        DenominationService denominationService = mock(DenominationService.class);
        Player player = mock(Player.class);

        when(player.hasPermission("economypol.admin")).thenReturn(true);
        when(economyService.withdrawCustodialAsPhysicalMoney(player, 90L, INVENTORY_ONLY_ROUTING))
                .thenReturn(MoneyOperationResult.success(90L, 54L, 36L, "Withdraw processed."));
        when(economyService.denominationService()).thenReturn(denominationService);
        when(denominationService.format(54L)).thenReturn("54 Gold Coins");
        when(denominationService.format(36L)).thenReturn("36 Gold Coins");

        WithdrawSubcommand subcommand = new WithdrawSubcommand(dependencies(economyService));

        subcommand.execute(player, new String[] {"90"});

        verify(economyService).withdrawCustodialAsPhysicalMoney(player, 90L, INVENTORY_ONLY_ROUTING);
        verify(economyService, never()).withdrawMaxCustodialToInventory(player);
        assertTrue(capturePlainText(player).contains("Withdraw processed. Delivered: 54 Gold Coins, retained: 36 Gold Coins"));
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

    private String capturePlainText(Player player) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(captor.capture());
        return PlainTextComponentSerializer.plainText().serialize(captor.getValue());
    }
}
