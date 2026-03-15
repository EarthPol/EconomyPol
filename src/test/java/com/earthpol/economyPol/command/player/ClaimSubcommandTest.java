package com.earthpol.economyPol.command.player;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.command.player.ClaimSubcommand;
import com.earthpol.economyPol.economy.command.shared.CommandDependencies;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.MoneyOperationFailureReason;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class ClaimSubcommandTest {

    @Test
    void claimWithdrawsMaximumThatFitsAndSendsClaimMessage() {
        EconomyService economyService = mock(EconomyService.class);
        DenominationService denominationService = mock(DenominationService.class);
        Player player = mock(Player.class);

        when(economyService.withdrawMaxCustodialToInventory(player))
                .thenReturn(MoneyOperationResult.success(81L, 81L, 0L, "Withdraw processed."));
        when(economyService.denominationService()).thenReturn(denominationService);
        when(denominationService.format(81L)).thenReturn("81 Gold Coins");

        ClaimSubcommand subcommand = new ClaimSubcommand(dependencies(economyService));

        subcommand.execute(player, new String[0]);

        verify(economyService).withdrawMaxCustodialToInventory(player);
        assertTrue(capturePlainText(player).contains("81 Gold Coins claimed from overflow account."));
    }

    @Test
    void claimRejectsArguments() {
        EconomyService economyService = mock(EconomyService.class);
        Player player = mock(Player.class);

        ClaimSubcommand subcommand = new ClaimSubcommand(dependencies(economyService));

        subcommand.execute(player, new String[] {"10"});

        verify(player).sendMessage("/claim");
    }

    @Test
    void claimWithNoOverflowMoneyShowsFriendlyMessage() {
        EconomyService economyService = mock(EconomyService.class);
        Player player = mock(Player.class);

        when(economyService.withdrawMaxCustodialToInventory(player))
                .thenReturn(MoneyOperationResult.failure(
                        0L,
                        "Insufficient custodial funds.",
                        MoneyOperationFailureReason.INSUFFICIENT_FUNDS
                ));

        ClaimSubcommand subcommand = new ClaimSubcommand(dependencies(economyService));

        subcommand.execute(player, new String[0]);

        assertTrue(capturePlainText(player).contains("You have no money in your overflow account."));
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
