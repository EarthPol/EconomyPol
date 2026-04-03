package com.earthpol.economyPol.service;

import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.AccountType;
import com.earthpol.economyPol.economy.repository.AccountRepository;
import com.earthpol.economyPol.economy.repository.PlayerRepository;
import com.earthpol.economyPol.economy.service.account.AccountRegistryService;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class AccountRegistryServiceTest {

    @Test
    void syncPlayerIdentityCreatesMissingPlayerAccount() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        PlayerRepository playerRepository = mock(PlayerRepository.class);
        PluginSettings settings = mock(PluginSettings.class);
        OfflinePlayer player = mock(OfflinePlayer.class);
        UUID playerUuid = UUID.randomUUID();

        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn("Alice");
        when(accountRepository.findPlayerAccount(playerUuid)).thenReturn(Optional.empty());
        when(accountRepository.findAccountByName(playerUuid.toString())).thenReturn(Optional.empty());

        AccountRegistryService service = new AccountRegistryService(accountRepository, playerRepository, settings);

        service.syncPlayerIdentity(player);

        verify(playerRepository).ensurePlayer(playerUuid, "Alice");
        verify(accountRepository).ensurePlayerAccount(playerUuid, "Alice");
    }

    @Test
    void syncPlayerIdentityDoesNotRewriteStableExistingPlayerAccount() {
        AccountRepository accountRepository = mock(AccountRepository.class);
        PlayerRepository playerRepository = mock(PlayerRepository.class);
        PluginSettings settings = mock(PluginSettings.class);
        OfflinePlayer player = mock(OfflinePlayer.class);
        UUID playerUuid = UUID.randomUUID();

        when(player.getUniqueId()).thenReturn(playerUuid);
        when(player.getName()).thenReturn("Alice");
        when(accountRepository.findPlayerAccount(playerUuid)).thenReturn(Optional.of(
                new AccountRecord(playerUuid, AccountType.PLAYER, playerUuid, playerUuid.toString())
        ));

        AccountRegistryService service = new AccountRegistryService(accountRepository, playerRepository, settings);

        service.syncPlayerIdentity(player);

        verify(playerRepository).ensurePlayer(playerUuid, "Alice");
        verify(accountRepository, never()).ensurePlayerAccount(playerUuid, "Alice");
    }
}
