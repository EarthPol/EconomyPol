package com.earthpol.economyPol.economy.service.player;

import com.earthpol.earthpollib.translation.TranslationService;
import com.earthpol.earthpollib.translation.Translations;
import com.earthpol.economyPol.economy.model.PlayerNotificationRecord;
import com.earthpol.economyPol.economy.model.PlayerNotificationType;
import com.earthpol.economyPol.economy.repository.NotificationRepository;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

// TODO: This whole class could be improved/simplified/rearchitectured
public final class NotificationService {

    private static final String WITHDRAW_COMMAND = "/claim";

    private final DenominationService denominationService;
    private final NotificationRepository repository;
    private final TranslationService translationService;

    public NotificationService(
            DenominationService denominationService,
            NotificationRepository repository,
            TranslationService translationService
    ) {
        this.denominationService = denominationService;
        this.repository = repository;
        this.translationService = translationService;
    }

    public void notifyIncomingOverflowToCustodial(Player player, long overflowAmount, long custodialBalance) {
        if (player == null || overflowAmount <= 0L) {
            return;
        }
        Locale locale = locale(player);
        send(player, "notifications.incoming_overflow.title", List.of(
                detail(locale, "notifications.incoming_overflow.moved", overflowAmount),
                detail(locale, "notifications.incoming_overflow.balance", custodialBalance),
                withdrawHint(locale, "notifications.incoming_overflow.hint")
        ), () -> queueIncomingOverflowToCustodial(player.getUniqueId(), overflowAmount, custodialBalance));
    }

    public void queueIncomingOverflowToCustodial(UUID playerUuid, long overflowAmount, long custodialBalance) {
        if (playerUuid == null || overflowAmount <= 0L) {
            return;
        }
        repository.createPlayerNotification(
                playerUuid,
                PlayerNotificationType.INCOMING_OVERFLOW_TO_CUSTODIAL,
                overflowAmount,
                custodialBalance,
                null,
                false
        );
    }

    public void notifyWithdrawalRetainedInCustodial(Player player, long retainedAmount, long custodialBalance) {
        if (player == null || retainedAmount <= 0L) {
            return;
        }
        Locale locale = locale(player);
        send(player, "notifications.withdrawal_retained.title", List.of(
                detail(locale, "notifications.withdrawal_retained.retained", retainedAmount),
                detail(locale, "notifications.withdrawal_retained.balance", custodialBalance),
                withdrawHint(locale, "notifications.withdrawal_retained.hint")
        ), () -> queueNotification(
                player.getUniqueId(),
                PlayerNotificationType.CUSTODIAL_WITHDRAWAL_RETAINED,
                retainedAmount,
                custodialBalance,
                false
        ));
    }

    public void notifyWalletOverflowToCustodial(
            Player player,
            long overflowAmount,
            long custodialBalance,
            boolean malformedStacksFound
    ) {
        if (player == null || overflowAmount <= 0L) {
            return;
        }
        Locale locale = locale(player);
        List<Component> details = new ArrayList<>();
        details.add(detail(locale, "notifications.wallet_overflow.moved", overflowAmount));
        details.add(detail(locale, "notifications.wallet_overflow.balance", custodialBalance));
        if (malformedStacksFound) {
            details.add(translated(locale, "notifications.wallet_overflow.malformed"));
        }
        details.add(withdrawHint(locale, "notifications.wallet_overflow.hint"));
        send(player, "notifications.wallet_overflow.title", details, () -> queueNotification(
                player.getUniqueId(),
                PlayerNotificationType.WALLET_OVERFLOW_TO_CUSTODIAL,
                overflowAmount,
                custodialBalance,
                malformedStacksFound
        ));
    }

    public void notifyCustodialBalanceReminder(Player player, long custodialBalance) {
        if (player == null || custodialBalance <= 0L) {
            return;
        }
        Locale locale = locale(player);
        send(player, "notifications.custodial_reminder.title", List.of(
                detail(locale, "notifications.custodial_reminder.available", custodialBalance),
                withdrawHint(locale, "notifications.custodial_reminder.hint")
        ), () -> queueNotification(
                player.getUniqueId(),
                PlayerNotificationType.CUSTODIAL_BALANCE_REMINDER,
                custodialBalance,
                null,
                false
        ));
    }

    public void notifyChangeRoutedToCustodial(Player player, long changeAmount, long custodialBalance) {
        if (player == null || changeAmount <= 0L) {
            return;
        }
        Locale locale = locale(player);
        send(player, "notifications.change_routed.title", List.of(
                detail(locale, "notifications.change_routed.moved", changeAmount),
                detail(locale, "notifications.change_routed.balance", custodialBalance),
                withdrawHint(locale, "notifications.change_routed.hint")
        ), () -> queueNotification(
                player.getUniqueId(),
                PlayerNotificationType.CHANGE_ROUTED_TO_CUSTODIAL,
                changeAmount,
                custodialBalance,
                false
        ));
    }

    public void queueOfflineCreditToCustodial(java.util.UUID playerUuid, long creditedAmount, long custodialBalance) {
        if (playerUuid == null || creditedAmount <= 0L) {
            return;
        }
        repository.createPlayerNotification(
                playerUuid,
                PlayerNotificationType.OFFLINE_CREDIT_TO_CUSTODIAL,
                creditedAmount,
                custodialBalance,
                null,
                false
        );
    }

    public void notifyNotEnoughRoomForChange(Player player) {
        if (player == null) {
            return;
        }
        Locale locale = locale(player);
        send(player, "notifications.not_enough_room_for_change.title", List.of(
                translated(locale, "notifications.not_enough_room_for_change.body")
        ), () -> queueNotification(
                player.getUniqueId(),
                PlayerNotificationType.NOT_ENOUGH_ROOM_FOR_CHANGE,
                null,
                null,
                false
        ));
    }

    public int deliverPendingNotifications(Player player) {
        if (player == null) {
            return 0;
        }
        List<PlayerNotificationRecord> queued = repository.listPlayerNotifications(player.getUniqueId());
        if (queued.isEmpty()) {
            return 0;
        }
        Locale locale = locale(player);
        List<Component> rendered = queued.stream().map(notification -> render(notification, locale)).toList();
        rendered.forEach(player::sendMessage);
        for (PlayerNotificationRecord notification : queued) {
            repository.deletePlayerNotification(notification.notificationId());
        }
        return queued.size();
    }

    private void send(Player player, String titleKey, List<Component> details, Runnable onFailure) {
        send(player, buildMessage(locale(player), titleKey, details), onFailure);
    }

    private void send(Player player, Component message, Runnable onFailure) {
        try {
            player.sendMessage(message);
        } catch (Throwable throwable) {
            if (onFailure != null) {
                onFailure.run();
            }
        }
    }

    private Component detail(Locale locale, String key, long amount) {
        return translated(locale, key, denominationService.format(amount));
    }

    private Component withdrawHint(Locale locale, String key) {
        return translated(locale, key, WITHDRAW_COMMAND)
                .clickEvent(ClickEvent.suggestCommand(WITHDRAW_COMMAND))
                .hoverEvent(HoverEvent.showText(translated(locale, "notifications.withdraw.hover")));
    }

    private Component render(PlayerNotificationRecord notification, Locale locale) {
        return switch (notification.notificationType()) {
            case INCOMING_OVERFLOW_TO_CUSTODIAL -> buildMessage(
                    locale,
                    "notifications.incoming_overflow.title",
                    List.of(
                            detail(locale, "notifications.incoming_overflow.moved", amount(notification.primaryAmount())),
                            detail(locale, "notifications.incoming_overflow.balance", amount(notification.secondaryAmount())),
                            withdrawHint(locale, "notifications.incoming_overflow.hint")
                    )
            );
            case CUSTODIAL_WITHDRAWAL_RETAINED -> buildMessage(
                    locale,
                    "notifications.withdrawal_retained.title",
                    List.of(
                            detail(locale, "notifications.withdrawal_retained.retained", amount(notification.primaryAmount())),
                            detail(locale, "notifications.withdrawal_retained.balance", amount(notification.secondaryAmount())),
                            withdrawHint(locale, "notifications.withdrawal_retained.hint")
                    )
            );
            case WALLET_OVERFLOW_TO_CUSTODIAL -> {
                List<Component> details = new ArrayList<>();
                details.add(detail(locale, "notifications.wallet_overflow.moved", amount(notification.primaryAmount())));
                details.add(detail(locale, "notifications.wallet_overflow.balance", amount(notification.secondaryAmount())));
                if (notification.flagValue()) {
                    details.add(translated(locale, "notifications.wallet_overflow.malformed"));
                }
                details.add(withdrawHint(locale, "notifications.wallet_overflow.hint"));
                yield buildMessage(locale, "notifications.wallet_overflow.title", details);
            }
            case CUSTODIAL_BALANCE_REMINDER -> buildMessage(
                    locale,
                    "notifications.custodial_reminder.title",
                    List.of(
                            detail(locale, "notifications.custodial_reminder.available", amount(notification.primaryAmount())),
                            withdrawHint(locale, "notifications.custodial_reminder.hint")
                    )
            );
            case OFFLINE_CREDIT_TO_CUSTODIAL -> buildMessage(
                    locale,
                    "notifications.offline_credit.title",
                    List.of(
                            detail(locale, "notifications.offline_credit.credited", amount(notification.primaryAmount())),
                            detail(locale, "notifications.offline_credit.balance", amount(notification.secondaryAmount())),
                            withdrawHint(locale, "notifications.offline_credit.hint")
                    )
            );
            case CHANGE_ROUTED_TO_CUSTODIAL -> buildMessage(
                    locale,
                    "notifications.change_routed.title",
                    List.of(
                            detail(locale, "notifications.change_routed.moved", amount(notification.primaryAmount())),
                            detail(locale, "notifications.change_routed.balance", amount(notification.secondaryAmount())),
                            withdrawHint(locale, "notifications.change_routed.hint")
                    )
            );
            case NOT_ENOUGH_ROOM_FOR_CHANGE -> buildMessage(
                    locale,
                    "notifications.not_enough_room_for_change.title",
                    List.of(
                            translated(locale, "notifications.not_enough_room_for_change.body")
                    )
            );
        };
    }

    private Component buildMessage(Locale locale, String titleKey, List<Component> details) {
        List<Component> lines = new ArrayList<>();
        lines.add(translated(locale, Translations.DEFAULT_PREFIX_KEY).append(translated(locale, titleKey)));
        lines.addAll(details);
        return Component.join(JoinConfiguration.separator(Component.newline()), lines);
    }

    private Component translated(Locale locale, String key, Object... args) {
        return Translations.component(translationService, locale, key, args);
    }

    private void queueNotification(
            java.util.UUID playerUuid,
            PlayerNotificationType type,
            Long primaryAmount,
            Long secondaryAmount,
            boolean flagValue
    ) {
        if (playerUuid == null) {
            return;
        }
        repository.createPlayerNotification(
                playerUuid,
                type,
                primaryAmount,
                secondaryAmount,
                null,
                flagValue
        );
    }

    private static long amount(Long value) {
        return value == null ? 0L : value;
    }

    private Locale locale(Player player) {
        Locale locale = player.locale();
        return locale == null ? translationService.getDefaultLocale() : locale;
    }
}

