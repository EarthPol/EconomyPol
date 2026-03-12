package com.earthpol.economyPol.command.player;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.command.shared.CommandDependencies;
import com.earthpol.economyPol.config.PluginSettings;
import com.earthpol.economyPol.model.EnderWalletSnapshot;
import com.earthpol.economyPol.model.OfflineEnderWalletState;
import com.earthpol.economyPol.service.DatabaseCheckService;
import com.earthpol.economyPol.service.DenominationService;
import com.earthpol.economyPol.service.EconomyService;
import com.earthpol.economyPol.service.EnderWalletService;
import com.earthpol.economyPol.service.SchedulerService;
import com.earthpol.economyPol.towny.TownyService;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class BalanceTopCacheTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void requestBuildsBalanceTopFromOnlinePlayersAndOfflineSnapshotsAndReusesFreshCache() {
        PlayerMock alice = server.addPlayer("Alice");
        PlayerMock bob = server.addPlayer("Bob");
        UUID carolUuid = UUID.randomUUID();

        EconomyService economyService = mock(EconomyService.class);
        EnderWalletService enderWalletService = mock(EnderWalletService.class);
        SchedulerService schedulerService = mock(SchedulerService.class);
        DenominationService denominationService = mock(DenominationService.class);
        PluginSettings settings = mock(PluginSettings.class);
        EnhancedLogger operationsLogger = mock(EnhancedLogger.class);
        EnhancedLogger healthcheckLogger = mock(EnhancedLogger.class);

        when(economyService.schedulerService()).thenReturn(schedulerService);
        when(economyService.denominationService()).thenReturn(denominationService);
        when(economyService.accountNameMap()).thenReturn(Map.of(carolUuid, "Carol"));
        when(economyService.scanOnlinePlayerMoney(alice)).thenReturn(50L);
        when(economyService.scanOnlinePlayerMoney(bob)).thenReturn(10L);
        when(enderWalletService.listFrozenSnapshots()).thenReturn(List.of(
                new EnderWalletSnapshot(carolUuid, 30L, OfflineEnderWalletState.FROZEN, 100L),
                new EnderWalletSnapshot(bob.getUniqueId(), 999L, OfflineEnderWalletState.FROZEN, 100L)
        ));
        when(denominationService.format(anyLong())).thenAnswer(invocation -> invocation.getArgument(0) + " Gold Coins");
        when(settings.cache()).thenReturn(new PluginSettings.CacheSettings(60L));
        when(schedulerService.runAsync(any(Runnable.class), anyString())).thenAnswer(invocation -> {
            Runnable action = invocation.getArgument(0);
            action.run();
            return true;
        });
        when(schedulerService.runOnCommandSenderContext(any(CommandSender.class), any(Runnable.class), anyString())).thenAnswer(invocation -> {
            Runnable action = invocation.getArgument(1);
            action.run();
            return true;
        });

        CommandDependencies dependencies = new CommandDependencies(
                economyService,
                enderWalletService,
                mock(DatabaseCheckService.class),
                new TownyService(),
                settings,
                operationsLogger,
                healthcheckLogger
        );
        BalanceTopCache cache = new BalanceTopCache(dependencies, 10);
        CommandSender firstSender = mock(CommandSender.class);
        CommandSender secondSender = mock(CommandSender.class);

        cache.request(firstSender);
        cache.request(secondSender);

        verify(schedulerService, times(1)).runAsync(any(Runnable.class), anyString());

        ArgumentCaptor<String> firstMessages = ArgumentCaptor.forClass(String.class);
        verify(firstSender, times(6)).sendMessage(firstMessages.capture());
        List<String> firstLines = firstMessages.getAllValues();
        assertTrue(firstLines.contains("Rebuilding the balancetop cache. You will receive the results when it completes."));
        assertTrue(firstLines.contains("1. Alice - 50 Gold Coins"));
        assertTrue(firstLines.contains("2. Carol - 30 Gold Coins"));
        assertTrue(firstLines.contains("3. Bob - 10 Gold Coins"));
        assertTrue(firstLines.stream().noneMatch(line -> line.contains("999 Gold Coins")));

        ArgumentCaptor<String> secondMessages = ArgumentCaptor.forClass(String.class);
        verify(secondSender, times(5)).sendMessage(secondMessages.capture());
        List<String> secondLines = secondMessages.getAllValues();
        assertTrue(secondLines.stream().noneMatch(line -> line.contains("Rebuilding the balancetop cache")));
        assertTrue(secondLines.contains("1. Alice - 50 Gold Coins"));
        assertTrue(secondLines.contains("2. Carol - 30 Gold Coins"));
        assertTrue(secondLines.contains("3. Bob - 10 Gold Coins"));
    }
}
