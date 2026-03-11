package com.earthpol.economyPol.service;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.config.PluginSettings;
import com.earthpol.economyPol.domain.Denomination;
import com.earthpol.economyPol.domain.PlayerNotificationRecord;
import com.earthpol.economyPol.domain.PlayerNotificationType;
import com.earthpol.economyPol.persistence.JdbcEconomyRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class NotificationServiceTest {

    private NotificationService notificationService;
    private JdbcEconomyRepository repository;
    private SchedulerService schedulerService;

    @BeforeEach
    void setUp() {
        DenominationService denominationService = new DenominationService(
                new PluginSettings.CurrencySettings(
                        "Gold Coin",
                        "Gold Coins",
                        List.of(
                                new Denomination(Material.GOLD_NUGGET, 1L),
                                new Denomination(Material.GOLD_INGOT, 9L),
                                new Denomination(Material.GOLD_BLOCK, 81L)
                        )
                ),
                null
        );
        repository = mock(JdbcEconomyRepository.class);
        schedulerService = mock(SchedulerService.class);
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(1);
            action.run();
            return true;
        }).when(schedulerService).runOnPlayerEntityScheduler(any(Player.class), any(Runnable.class), anyString());
        notificationService = new NotificationService(denominationService, repository, schedulerService, mock(EnhancedLogger.class));
    }

    @Test
    void incomingOverflowNotificationIncludesCustodialAmountsAndWithdrawHint() {
        Player player = mock(Player.class);

        notificationService.notifyIncomingOverflowToCustodial(player, 18L, 125L);

        String message = capturePlainText(player);
        assertTrue(message.contains("Incoming Money Routed to Custodial"));
        assertTrue(message.contains("Moved to custodial: 18 Gold Coins"));
        assertTrue(message.contains("Custodial balance: 125 Gold Coins"));
        assertTrue(message.contains("/economypol withdraw <amount>"));
    }

    @Test
    void custodialBalanceReminderIncludesBalanceAndWithdrawHint() {
        Player player = mock(Player.class);

        notificationService.notifyCustodialBalanceReminder(player, 42L);

        String message = capturePlainText(player);
        assertTrue(message.contains("Custodial Balance Available"));
        assertTrue(message.contains("Available in custodial: 42 Gold Coins"));
        assertTrue(message.contains("Withdraw it when you want to carry it as physical money."));
        assertTrue(message.contains("/economypol withdraw <amount>"));
    }

    @Test
    void custodialBalanceReminderSkipsZeroOrNegativeBalances() {
        Player player = mock(Player.class);

        notificationService.notifyCustodialBalanceReminder(player, 0L);
        notificationService.notifyCustodialBalanceReminder(player, -5L);

        verifyNoInteractions(player);
    }

    @Test
    void offlineCustodialCreditQueuesNotification() {
        UUID playerUuid = UUID.randomUUID();

        notificationService.queueOfflineCreditToCustodial(playerUuid, 25L, 90L);

        verify(repository).createPlayerNotification(
                eq(playerUuid),
                eq(PlayerNotificationType.OFFLINE_CREDIT_TO_CUSTODIAL),
                eq(25L),
                eq(90L),
                isNull(),
                eq(false)
        );
    }

    @Test
    void notEnoughRoomForChangeNotificationExplainsCanceledTransaction() {
        Player player = mock(Player.class);

        notificationService.notifyNotEnoughRoomForChange(player);

        String message = capturePlainText(player);
        assertTrue(message.contains("Not Enough Room For Change"));
        assertTrue(message.contains("This transaction was canceled because you do not have enough space"));
        assertTrue(message.contains("inventory and/or ender chest"));
    }

    @Test
    void deliverPendingNotificationsSendsQueuedMessagesAndDeletesThem() {
        UUID playerUuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerUuid);
        when(repository.listPlayerNotifications(playerUuid)).thenReturn(List.of(
                new PlayerNotificationRecord(1L, playerUuid, PlayerNotificationType.OFFLINE_CREDIT_TO_CUSTODIAL, 25L, 90L, null, false, 1L),
                new PlayerNotificationRecord(2L, playerUuid, PlayerNotificationType.INCOMING_OVERFLOW_TO_CUSTODIAL, 4L, 94L, null, false, 2L)
        ));

        int delivered = notificationService.deliverPendingNotifications(player);

        assertEquals(2, delivered);
        verify(player, times(2)).sendMessage(any(Component.class));
        verify(repository).deletePlayerNotification(1L);
        verify(repository).deletePlayerNotification(2L);
    }

    private static String capturePlainText(Player player) {
        ArgumentCaptor<Component> captor = ArgumentCaptor.forClass(Component.class);
        verify(player).sendMessage(captor.capture());
        return PlainTextComponentSerializer.plainText().serialize(captor.getValue());
    }
}
