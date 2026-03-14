package com.earthpol.economyPol.command.player;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.command.player.PaymentDeliverySubcommand;
import com.earthpol.economyPol.economy.command.shared.CommandDependencies;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.IncomingPaymentDeliveryPreference;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.databasecheck.DatabaseCheckService;
import com.earthpol.economyPol.economy.service.player.EnderWalletService;
import com.earthpol.economyPol.towny.TownyService;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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

        verify(player).sendMessage(eq("Current incoming payment delivery preference: skipinventory"));
        verify(player).sendMessage(eq("Effective routing: Ender chest -> Custodial"));
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

        subcommand.execute(player, new String[] {"skipinventoryandenderchest"});

        verify(economyService).setIncomingPaymentDeliveryPreference(
                player,
                IncomingPaymentDeliveryPreference.SKIP_INVENTORY_AND_ENDERCHEST
        );
        verify(player).sendMessage(contains("skipinventoryandenderchest"));
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
