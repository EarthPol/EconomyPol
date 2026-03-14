package com.earthpol.economyPol.service;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.earthPolLib.translation.TranslationService;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.Denomination;
import com.earthpol.economyPol.economy.model.PlayerNotificationRecord;
import com.earthpol.economyPol.economy.model.PlayerNotificationType;
import com.earthpol.economyPol.economy.repository.NotificationRepository;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import com.earthpol.economyPol.economy.service.player.NotificationService;
import com.earthpol.economyPol.economy.service.support.SchedulerService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class NotificationServiceTest {

    private NotificationService notificationService;
    private NotificationRepository repository;
    private SchedulerService schedulerService;
    private TranslationService translationService;

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
        repository = mock(NotificationRepository.class);
        schedulerService = mock(SchedulerService.class);
        translationService = mock(TranslationService.class);
        doAnswer(invocation -> {
            Runnable action = invocation.getArgument(1);
            action.run();
            return true;
        }).when(schedulerService).runOnPlayerEntityScheduler(any(Player.class), any(Runnable.class), anyString());
        lenient().when(translationService.getDefaultLocale()).thenReturn(Locale.US);
        lenient().doAnswer(invocation -> translate(invocation.getArgument(0, String.class), new Object[0]))
                .when(translationService).translate(anyString(), any(Locale.class));
        lenient().doAnswer(invocation -> translate(
                        invocation.getArgument(0, String.class),
                        Arrays.copyOfRange(invocation.getArguments(), 2, invocation.getArguments().length)
                ))
                .when(translationService).translate(anyString(), any(Locale.class), org.mockito.ArgumentMatchers.<Object>any());
        notificationService = new NotificationService(
                denominationService,
                repository,
                schedulerService,
                translationService,
                mock(EnhancedLogger.class)
        );
    }

    @Test
    void incomingOverflowNotificationIncludesCustodialAmountsAndWithdrawHint() {
        Player player = mock(Player.class);

        notificationService.notifyIncomingOverflowToCustodial(player, 18L, 125L);

        String message = capturePlainText(player);
        assertTrue(message.contains("Incoming Money Routed to Custodial"));
        assertTrue(message.contains("Moved to custodial: 18 Gold Coins"));
        assertTrue(message.contains("Custodial balance: 125 Gold Coins"));
        assertTrue(message.contains("/economypol withdraw"));
    }

    @Test
    void custodialBalanceReminderIncludesBalanceAndWithdrawHint() {
        Player player = mock(Player.class);

        notificationService.notifyCustodialBalanceReminder(player, 42L);

        String message = capturePlainText(player);
        assertTrue(message.contains("Custodial Balance Available"));
        assertTrue(message.contains("Available in custodial: 42 Gold Coins"));
        assertTrue(message.contains("carry custodial money as physical currency"));
        assertTrue(message.contains("/economypol withdraw"));
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
    void changeRoutedToCustodialNotificationExplainsReturnedChange() {
        Player player = mock(Player.class);

        notificationService.notifyChangeRoutedToCustodial(player, 71L, 90L);

        String message = capturePlainText(player);
        assertTrue(message.contains("Change Routed to Custodial"));
        assertTrue(message.contains("Change moved to custodial: 71 Gold Coins"));
        assertTrue(message.contains("Custodial balance: 90 Gold Coins"));
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

    private static String translate(String key, Object[] args) {
        Map<String, String> translations = Map.ofEntries(
                Map.entry("general.prefix", "[EconomyPol] "),
                Map.entry("notifications.withdraw.hover", "Withdraw as much custodial money as fits into your inventory."),
                Map.entry("notifications.incoming_overflow.title", "Incoming Money Routed to Custodial"),
                Map.entry("notifications.incoming_overflow.moved", "Moved to custodial: {0}"),
                Map.entry("notifications.incoming_overflow.balance", "Custodial balance: {0}"),
                Map.entry("notifications.incoming_overflow.hint", "Some incoming money could not fit in your inventory or ender chest. Use {0} to withdraw physical money."),
                Map.entry("notifications.withdrawal_retained.title", "Withdraw Overflow Retained"),
                Map.entry("notifications.withdrawal_retained.retained", "Retained in custodial: {0}"),
                Map.entry("notifications.withdrawal_retained.balance", "Custodial balance: {0}"),
                Map.entry("notifications.withdrawal_retained.hint", "Some withdrawn money could not fit in your inventory. Use {0} to physicalize as much as currently fits."),
                Map.entry("notifications.wallet_overflow.title", "Ender Wallet Overflow"),
                Map.entry("notifications.wallet_overflow.moved", "Moved to custodial: {0}"),
                Map.entry("notifications.wallet_overflow.balance", "Custodial balance: {0}"),
                Map.entry("notifications.wallet_overflow.malformed", "Malformed money stacks were normalized before the overflow was stored."),
                Map.entry("notifications.wallet_overflow.hint", "Some ender-wallet money could not be restored cleanly and was moved to custodial. Use {0} to withdraw physical money."),
                Map.entry("notifications.custodial_reminder.title", "Custodial Balance Available"),
                Map.entry("notifications.custodial_reminder.available", "Available in custodial: {0}"),
                Map.entry("notifications.custodial_reminder.hint", "Use {0} when you want to carry custodial money as physical currency."),
                Map.entry("notifications.offline_credit.title", "Money Received While Offline"),
                Map.entry("notifications.offline_credit.credited", "Credited to custodial: {0}"),
                Map.entry("notifications.offline_credit.balance", "Custodial balance: {0}"),
                Map.entry("notifications.offline_credit.hint", "Money received while you were offline was stored safely in custodial. Use {0} to physicalize it."),
                Map.entry("notifications.change_routed.title", "Change Routed to Custodial"),
                Map.entry("notifications.change_routed.moved", "Change moved to custodial: {0}"),
                Map.entry("notifications.change_routed.balance", "Custodial balance: {0}"),
                Map.entry("notifications.change_routed.hint", "Some returned change could not fit in your inventory or ender chest. Use {0} to physicalize it later."),
                Map.entry("notifications.not_enough_room_for_change.title", "Not Enough Room For Change"),
                Map.entry("notifications.not_enough_room_for_change.body", "This transaction was canceled because you do not have enough space in your inventory and/or ender chest to receive your change back.")
        );
        String template = translations.getOrDefault(key, key);
        return java.text.MessageFormat.format(template, args == null ? new Object[0] : args);
    }
}

