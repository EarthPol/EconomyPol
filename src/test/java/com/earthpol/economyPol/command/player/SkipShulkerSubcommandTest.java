package com.earthpol.economyPol.command.player;

import com.earthpol.economyPol.economy.command.player.SkipShulkerSubcommand;
import com.earthpol.economyPol.economy.command.shared.CommandDependencies;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.databasecheck.DatabaseCheckService;
import com.earthpol.economyPol.economy.service.player.EnderWalletService;
import com.earthpol.economyPol.towny.TownyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class SkipShulkerSubcommandTest {

    @Test
    void noArgsShowsCurrentPreference() {
        EconomyService economyService = mock(EconomyService.class);
        Player player = mock(Player.class);

        when(economyService.getSkipShulkerDelivery(player)).thenReturn(true);

        SkipShulkerSubcommand subcommand = new SkipShulkerSubcommand(dependencies(economyService));

        subcommand.execute(player, new String[0]);

        List<String> messages = capturePlainTextMessages(player, 3);
        assertTrue(messages.get(0).contains("[EconomyPol] Current skip_shulker setting: true"));
        assertTrue(messages.get(1).contains("When false, automatic incoming money can fill top-level yellow shulkers first."));
        assertTrue(messages.get(2).contains("Usage: /economypol skip_shulker <true|false>"));
        verify(economyService, never()).setSkipShulkerDelivery(player, true);
    }

    @Test
    void validPreferenceUpdatesPlayerSetting() {
        EconomyService economyService = mock(EconomyService.class);
        Player player = mock(Player.class);

        SkipShulkerSubcommand subcommand = new SkipShulkerSubcommand(dependencies(economyService));

        subcommand.execute(player, new String[] {"false"});

        verify(economyService).setSkipShulkerDelivery(player, false);
        verify(player).sendMessage(eq("skip_shulker set to false."));
        verify(player).sendMessage(eq("Automatic incoming money and returned change can flow into yellow shulkers first."));
    }

    @Test
    void invalidPreferenceShowsUsage() {
        EconomyService economyService = mock(EconomyService.class);
        Player player = mock(Player.class);

        SkipShulkerSubcommand subcommand = new SkipShulkerSubcommand(dependencies(economyService));

        subcommand.execute(player, new String[] {"maybe"});

        verify(player).sendMessage("/economypol skip_shulker <true|false>");
        verify(economyService, never()).setSkipShulkerDelivery(player, true);
        verify(economyService, never()).setSkipShulkerDelivery(player, false);
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

    private List<String> capturePlainTextMessages(Player player, int expectedCalls) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player, org.mockito.Mockito.times(expectedCalls)).sendMessage(captor.capture());
        return captor.getAllValues().stream()
                .map(component -> PlainTextComponentSerializer.plainText().serialize(component))
                .toList();
    }
}
