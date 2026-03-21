package com.earthpol.economyPol.vault;

import com.earthpol.economyPol.EconomyPol;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.AccountType;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.support.NumericalConsistencyService;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
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
                "Alice"
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
        OfflinePlayer player = mock(OfflinePlayer.class);
        UUID playerUuid = UUID.randomUUID();

        when(player.getUniqueId()).thenReturn(playerUuid);
        when(economyService.findAccount(playerUuid)).thenReturn(Optional.empty());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer("GhostPlayer")).thenReturn(player);

            assertFalse(adapter.hasAccount("GhostPlayer"));
        }

        verify(economyService).findAccount(playerUuid);
        verifyNoMoreInteractions(economyService);
    }

    @Test
    void hasAccountByNameResolvesCurrentUsernameToUuidBackedPlayerAccount() {
        EconomyService economyService = mock(EconomyService.class);
        EconomyVaultAdapter adapter = newAdapter(economyService);
        OfflinePlayer player = mock(OfflinePlayer.class);
        UUID playerUuid = UUID.randomUUID();
        AccountRecord account = new AccountRecord(playerUuid, AccountType.PLAYER, playerUuid, playerUuid.toString());

        when(player.getUniqueId()).thenReturn(playerUuid);
        when(economyService.findAccount(playerUuid)).thenReturn(Optional.of(account));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer("Alice")).thenReturn(player);

            assertTrue(adapter.hasAccount("Alice"));
        }

        verify(economyService).findAccount(playerUuid);
        verify(economyService, never()).findAccountByName("Alice");
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
                "town-bank"
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

    @Test
    void createPlayerAccountByNameRejectsGhostOfflinePlayer() {
        EconomyService economyService = mock(EconomyService.class);
        EconomyVaultAdapter adapter = newAdapter(economyService);
        OfflinePlayer player = mock(OfflinePlayer.class);

        when(player.isOnline()).thenReturn(false);
        when(player.hasPlayedBefore()).thenReturn(false);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer("GhostPlayer")).thenReturn(player);

            assertFalse(adapter.createPlayerAccount("GhostPlayer"));
        }

        verify(economyService, never()).ensurePlayerAccount(player);
    }

    @Test
    void createPlayerAccountByNameReturnsFalseWhenAccountCreationFails() {
        EconomyService economyService = mock(EconomyService.class);
        EconomyVaultAdapter adapter = newAdapter(economyService);
        OfflinePlayer player = mock(OfflinePlayer.class);

        when(player.isOnline()).thenReturn(false);
        when(player.hasPlayedBefore()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(economyService.ensurePlayerAccount(player)).thenThrow(new IllegalStateException("name collision"));

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getOfflinePlayer("Alice")).thenReturn(player);

            assertFalse(adapter.createPlayerAccount("Alice"));
        }

        verify(economyService).ensurePlayerAccount(player);
    }

    @Test
    void createPlayerAccountOfflinePlayerAllowsKnownOfflinePlayer() {
        EconomyService economyService = mock(EconomyService.class);
        EconomyVaultAdapter adapter = newAdapter(economyService);
        OfflinePlayer player = mock(OfflinePlayer.class);
        AccountRecord account = new AccountRecord(UUID.randomUUID(), AccountType.PLAYER, UUID.randomUUID(), "Alice");

        when(player.isOnline()).thenReturn(false);
        when(player.hasPlayedBefore()).thenReturn(true);
        when(economyService.ensurePlayerAccount(player)).thenReturn(account);

        assertTrue(adapter.createPlayerAccount(player));
        verify(economyService).ensurePlayerAccount(player);
    }

    private static EconomyVaultAdapter newAdapter(EconomyService economyService) {
        return new EconomyVaultAdapter(
                mock(EconomyPol.class),
                economyService,
                new NumericalConsistencyService(
                        new PluginSettings.NumericSettings(PluginSettings.DecimalHandlingMode.REJECT),
                        new DenominationService(new PluginSettings.CurrencySettings("Gold Coin", "Gold Coins", List.of()), null)
                ),
                new PluginSettings.CurrencySettings("Gold Coin", "Gold Coins", List.of()),
                mock(EconomyLoggers.class)
        );
    }
}

