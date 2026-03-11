package com.earthpol.economyPol.vault;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.EconomyPol;
import com.earthpol.economyPol.config.PluginSettings;
import com.earthpol.economyPol.domain.MoneyOperationResult;
import com.earthpol.economyPol.service.DenominationService;
import com.earthpol.economyPol.service.EconomyService;
import com.earthpol.economyPol.service.NumericalConsistencyService;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

final class EconomyVaultAdapterTest {

    @Test
    void withdrawPlayerRejectsFractionalAmounts() {
        EconomyService economyService = mock(EconomyService.class);
        OfflinePlayer player = mock(OfflinePlayer.class);
        EconomyVaultAdapter adapter = newAdapter(economyService);

        when(economyService.getBalance(player)).thenReturn(42L);

        EconomyResponse response = adapter.withdrawPlayer(player, 5.5D);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertEquals("Fractional amounts are not supported.", response.errorMessage);
        assertEquals(42D, response.balance);
        verify(economyService).getBalance(player);
        verifyNoMoreInteractions(economyService);
    }

    @Test
    void bankDepositRejectsFractionalAmounts() {
        EconomyService economyService = mock(EconomyService.class);
        EconomyVaultAdapter adapter = newAdapter(economyService);

        when(economyService.bankBalance("town")).thenReturn(100L);

        EconomyResponse response = adapter.bankDeposit("town", 10.25D);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertEquals("Fractional amounts are not supported.", response.errorMessage);
        assertEquals(100D, response.balance);
        verify(economyService).bankBalance("town");
        verifyNoMoreInteractions(economyService);
    }

    @Test
    void depositPlayerAcceptsWholeNumberAmountsWithoutRounding() {
        EconomyService economyService = mock(EconomyService.class);
        OfflinePlayer player = mock(OfflinePlayer.class);
        EconomyVaultAdapter adapter = newAdapter(economyService);
        MoneyOperationResult result = MoneyOperationResult.success(5L, 5L, 0L, "Funds delivered.");

        when(economyService.depositPlayer(player, 5L, "VAULT_DEPOSIT")).thenReturn(result);
        when(economyService.getBalance(player)).thenReturn(25L);

        EconomyResponse response = adapter.depositPlayer(player, 5.0D);

        assertEquals(EconomyResponse.ResponseType.SUCCESS, response.type);
        assertEquals("Funds delivered.", response.errorMessage);
        assertEquals(25D, response.balance);
        verify(economyService).depositPlayer(player, 5L, "VAULT_DEPOSIT");
        verify(economyService).getBalance(player);
    }

    private static EconomyVaultAdapter newAdapter(EconomyService economyService) {
        return new EconomyVaultAdapter(
                mock(EconomyPol.class),
                economyService,
                new NumericalConsistencyService(
                        new PluginSettings.NumericSettings(PluginSettings.DecimalHandlingMode.REJECT, RoundingMode.HALF_UP),
                        new DenominationService(new PluginSettings.CurrencySettings("Gold Coin", "Gold Coins", List.of()), null)
                ),
                new PluginSettings.CurrencySettings("Gold Coin", "Gold Coins", List.of()),
                mock(EnhancedLogger.class)
        );
    }
}
