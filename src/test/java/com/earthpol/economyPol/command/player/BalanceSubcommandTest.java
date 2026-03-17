package com.earthpol.economyPol.command.player;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.command.player.BalanceSubcommand;
import com.earthpol.economyPol.economy.command.shared.CommandDependencies;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.PlayerBalanceView;
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

final class BalanceSubcommandTest {

    @Test
    void playerFacingBalanceShowsInventoryEnderAndOverflow() {
        EconomyService economyService = mock(EconomyService.class);
        DenominationService denominationService = mock(DenominationService.class);
        Player player = mock(Player.class);

        when(economyService.balanceView(player)).thenReturn(new PlayerBalanceView(40L, 0L, 54L, 36L, 0L, false));
        when(economyService.denominationService()).thenReturn(denominationService);
        when(denominationService.format(90L)).thenReturn("90 Gold Coins");
        when(denominationService.format(54L)).thenReturn("54 Gold Coins");
        when(denominationService.format(36L)).thenReturn("36 Gold Coins");
        when(denominationService.format(40L)).thenReturn("40 Gold Coins");

        BalanceSubcommand subcommand = new BalanceSubcommand(dependencies(economyService));

        subcommand.execute(player, new String[0]);

        String message = capturePlainText(player);
        assertTrue(message.contains("[EconomyPol] Your Balance:"));
        assertTrue(message.contains("Spendable: 90 Gold Coins (Inventory + enderchest)"));
        assertTrue(message.contains("Inventory: 54 Gold Coins"));
        assertTrue(message.contains("Enderchest: 36 Gold Coins"));
        assertTrue(message.contains("Overflow account: 40 Gold Coins Not spendable. Must withdrawn to spend"));
        assertTrue(message.contains("Claim overflow balance with /claim"));
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
