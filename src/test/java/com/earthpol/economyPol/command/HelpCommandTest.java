package com.earthpol.economyPol.command;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.command.EconomyCommand;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.databasecheck.DatabaseCheckService;
import com.earthpol.economyPol.economy.service.player.EnderWalletService;
import com.earthpol.economyPol.towny.TownyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class HelpCommandTest {

    @Test
    void rootCommandShowsStyledPlayerHelp() {
        Player sender = mock(Player.class);
        when(sender.hasPermission("economypol.admin")).thenReturn(false);

        EconomyCommand command = command();

        command.onCommand(sender, rootCommand(), "economypol", new String[0]);

        List<String> messages = capturePlainTextMessages(sender, 1);
        assertTrue(messages.getFirst().contains("[EconomyPol] Commands"));
        assertTrue(messages.getFirst().contains("/bal"));
        assertTrue(messages.getFirst().contains("/baltop"));
        assertTrue(messages.getFirst().contains("/claim"));
        assertTrue(messages.getFirst().contains("/compress"));
        assertTrue(messages.getFirst().contains("/economypol help"));
        assertTrue(messages.getFirst().contains("skip_inventory"));
        assertTrue(messages.getFirst().contains("skip_inventory_and_enderchest"));
        assertFalse(messages.getFirst().contains("/economypol balance"));
        assertFalse(messages.getFirst().contains("/economypol balancetop"));
        assertFalse(messages.getFirst().contains("/economypol withdraw"));
        assertFalse(messages.getFirst().contains("/economypol normalizewallet"));
        assertTrue(messages.getFirst().contains("/ecopol works in place of /economypol."));
    }

    @Test
    void helpSubcommandShowsAdminSectionForAdmins() {
        Player sender = mock(Player.class);
        when(sender.hasPermission("economypol.admin")).thenReturn(true);

        EconomyCommand command = command();

        command.onCommand(sender, rootCommand(), "economypol", new String[] {"help"});

        List<String> messages = capturePlainTextMessages(sender, 2);
        assertTrue(messages.get(0).contains("[EconomyPol] Commands"));
        assertTrue(messages.get(1).contains("[EconomyPol] Admin Commands"));
        assertTrue(messages.get(1).contains("/economypol admin balance <player>"));
        assertTrue(messages.get(1).contains("/economypol admin reload"));
        assertTrue(messages.get(1).contains("/economypol withdraw <amount>"));
    }

    private EconomyCommand command() {
        return new EconomyCommand(
                mock(EconomyService.class),
                mock(EnderWalletService.class),
                mock(DatabaseCheckService.class),
                mock(TownyService.class),
                mock(PluginSettings.class),
                mock(EnhancedLogger.class),
                mock(EnhancedLogger.class)
        );
    }

    private Command rootCommand() {
        Command command = mock(Command.class);
        when(command.getName()).thenReturn("economypol");
        return command;
    }

    private List<String> capturePlainTextMessages(Player sender, int expectedCalls) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(sender, times(expectedCalls)).sendMessage(captor.capture());
        assertEquals(expectedCalls, captor.getAllValues().size());
        return captor.getAllValues().stream()
                .map(component -> PlainTextComponentSerializer.plainText().serialize(component))
                .toList();
    }
}
