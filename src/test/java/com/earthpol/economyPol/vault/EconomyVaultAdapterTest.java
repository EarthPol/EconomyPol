package com.earthpol.economyPol.vault;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.EconomyPol;
import com.earthpol.economyPol.config.PluginSettings;
import com.earthpol.economyPol.model.AccountRecord;
import com.earthpol.economyPol.model.AccountType;
import com.earthpol.economyPol.model.MoneyOperationResult;
import com.earthpol.economyPol.model.PlayerAccountPolicy;
import com.earthpol.economyPol.service.DenominationService;
import com.earthpol.economyPol.service.EconomyService;
import com.earthpol.economyPol.service.NumericalConsistencyService;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        UUID playerUuid = UUID.randomUUID();
        AccountRecord account = new AccountRecord(
                playerUuid,
                AccountType.PLAYER,
                playerUuid,
                "Alice",
                new PlayerAccountPolicy(false, true, true)
        );

        when(player.getUniqueId()).thenReturn(playerUuid);
        when(economyService.findAccount(playerUuid)).thenReturn(Optional.of(account));
        when(economyService.getBalance(playerUuid)).thenReturn(42L);

        EconomyResponse response = adapter.withdrawPlayer(player, 5.5D);

        assertEquals(EconomyResponse.ResponseType.FAILURE, response.type);
        assertEquals("Fractional amounts are not supported.", response.errorMessage);
        assertEquals(42D, response.balance);
        verify(economyService).findAccount(playerUuid);
        verify(economyService).getBalance(playerUuid);
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

    @Test
    void hasAccountByNameDoesNotCreateGhostPlayerAccounts() {
        EconomyService economyService = mock(EconomyService.class);
        EconomyVaultAdapter adapter = newAdapter(economyService);

        when(economyService.findAccountByName("GhostPlayer")).thenReturn(Optional.empty());

        assertFalse(adapter.hasAccount("GhostPlayer"));
        verify(economyService).findAccountByName("GhostPlayer");
        verifyNoMoreInteractions(economyService);
    }

    @Test
    void isBankMemberUsesSharedAccountMembershipInsteadOfOwnerOnly() {
        EconomyService economyService = mock(EconomyService.class);
        OfflinePlayer player = mock(OfflinePlayer.class);
        EconomyVaultAdapter adapter = newAdapter(economyService);
        UUID accountId = UUID.randomUUID();
        UUID playerUuid = UUID.randomUUID();
        AccountRecord bank = new AccountRecord(
                accountId,
                AccountType.SHARED,
                UUID.randomUUID(),
                "town-bank",
                new PlayerAccountPolicy(true, true, true)
        );

        when(player.getUniqueId()).thenReturn(playerUuid);
        when(economyService.findSharedAccount("town-bank")).thenReturn(Optional.of(bank));
        when(economyService.isAccountMember(accountId, playerUuid)).thenReturn(true);
        when(economyService.bankBalance("town-bank")).thenReturn(250L);

        EconomyResponse response = adapter.isBankMember("town-bank", player);

        assertEquals(EconomyResponse.ResponseType.SUCCESS, response.type);
        assertEquals(250D, response.balance);
        verify(economyService).findSharedAccount("town-bank");
        verify(economyService).isAccountMember(accountId, playerUuid);
        verify(economyService).bankBalance("town-bank");
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
