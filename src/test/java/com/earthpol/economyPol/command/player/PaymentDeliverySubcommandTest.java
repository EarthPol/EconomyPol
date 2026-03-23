package com.earthpol.economyPol.command.player;

import com.earthpol.economyPol.economy.command.player.PaymentDeliverySubcommand;
import com.earthpol.economyPol.economy.command.shared.CommandDependencies;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.model.IncomingPaymentDeliveryPreference;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class PaymentDeliverySubcommandTest {

    @Test
    void noArgsShowsCurrentPreference() {
        EconomyService economyService = mock(EconomyService.class);
        Player player = mock(Player.class);

        when(economyService.getIncomingPaymentDeliveryPreference(player))
                .thenReturn(IncomingPaymentDeliveryPreference.SKIP_INVENTORY);

        PaymentDeliverySubcommand subcommand = new PaymentDeliverySubcommand(dependencies(economyService));

        subcommand.execute(player, new String[0]);

        List<String> messages = capturePlainTextMessages(player, 3);
        assertTrue(messages.get(0).contains("[EconomyPol] Current incoming payment delivery preference: skip_inventory"));
        assertTrue(messages.get(1).contains("Default order: Ender chest -> Custodial"));
        assertTrue(messages.get(2).contains("Usage: /economypol paymentdelivery <default|skip_inventory|skip_inventory_and_enderchest>"));
        verify(economyService, never()).setIncomingPaymentDeliveryPreference(
                player,
                IncomingPaymentDeliveryPreference.SKIP_INVENTORY
        );
    }

    @Test
    void validPreferenceUpdatesPlayerSetting() {
        EconomyService economyService = mock(EconomyService.class);
        Player player = mock(Player.class);

        PaymentDeliverySubcommand subcommand = new PaymentDeliverySubcommand(dependencies(economyService));

        subcommand.execute(player, new String[] {"skip_inventory_and_enderchest"});

        verify(economyService).setIncomingPaymentDeliveryPreference(
                player,
                IncomingPaymentDeliveryPreference.SKIP_INVENTORY_AND_ENDERCHEST
        );
        verify(player).sendMessage(contains("skip_inventory_and_enderchest"));
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
