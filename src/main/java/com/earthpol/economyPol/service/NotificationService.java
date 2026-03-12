package com.earthpol.economyPol.service;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.domain.PlayerNotificationRecord;
import com.earthpol.economyPol.domain.PlayerNotificationType;
import com.earthpol.economyPol.persistence.NotificationRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public final class NotificationService {

    private static final String WITHDRAW_COMMAND = "/economypol withdraw <amount>";

    private final DenominationService denominationService;
    private final NotificationRepository repository;
    private final SchedulerService schedulerService;
    private final EnhancedLogger operationsLog;

    public NotificationService(
            DenominationService denominationService,
            NotificationRepository repository,
            SchedulerService schedulerService,
            EnhancedLogger operationsLog
    ) {
        this.denominationService = denominationService;
        this.repository = repository;
        this.schedulerService = schedulerService;
        this.operationsLog = operationsLog;
    }

    public void notifyIncomingOverflowToCustodial(Player player, long overflowAmount, long custodialBalance) {
        if (player == null || overflowAmount <= 0L) {
            return;
        }
        send(player, "Incoming Money Routed to Custodial", List.of(
                detail("Moved to custodial: ", overflowAmount),
                detail("Custodial balance: ", custodialBalance),
                withdrawHint("Some incoming money could not fit in your inventory or ender chest.")
        ), () -> queueIncomingOverflowToCustodial(player.getUniqueId(), overflowAmount, custodialBalance), "notify-incoming-overflow");
    }

    public void queueIncomingOverflowToCustodial(java.util.UUID playerUuid, long overflowAmount, long custodialBalance) {
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
        send(player, "Withdraw Overflow Retained", List.of(
                detail("Retained in custodial: ", retainedAmount),
                detail("Custodial balance: ", custodialBalance),
                withdrawHint("Some withdrawn money could not fit in your inventory or ender chest.")
        ), () -> queueNotification(
                player.getUniqueId(),
                PlayerNotificationType.CUSTODIAL_WITHDRAWAL_RETAINED,
                retainedAmount,
                custodialBalance,
                false
        ), "notify-withdrawal-retained");
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
        List<Component> details = new ArrayList<>();
        details.add(detail("Moved to custodial: ", overflowAmount));
        details.add(detail("Custodial balance: ", custodialBalance));
        if (malformedStacksFound) {
            details.add(Component.text("Malformed money stacks were normalized before the overflow was stored.", NamedTextColor.GRAY));
        }
        details.add(withdrawHint("Some ender-wallet money could not be restored cleanly and was moved to custodial."));
        send(player, "Ender Wallet Overflow", details, () -> queueNotification(
                player.getUniqueId(),
                PlayerNotificationType.WALLET_OVERFLOW_TO_CUSTODIAL,
                overflowAmount,
                custodialBalance,
                malformedStacksFound
        ), "notify-wallet-overflow");
    }

    public void notifyCustodialBalanceReminder(Player player, long custodialBalance) {
        if (player == null || custodialBalance <= 0L) {
            return;
        }
        send(player, "Custodial Balance Available", List.of(
                detail("Available in custodial: ", custodialBalance),
                withdrawHint("Withdraw it when you want to carry it as physical money.")
        ), () -> queueNotification(
                player.getUniqueId(),
                PlayerNotificationType.CUSTODIAL_BALANCE_REMINDER,
                custodialBalance,
                null,
                false
        ), "notify-custodial-reminder");
    }

    public void notifyChangeRoutedToCustodial(Player player, long changeAmount, long custodialBalance) {
        if (player == null || changeAmount <= 0L) {
            return;
        }
        send(player, "Change Routed to Custodial", List.of(
                detail("Change moved to custodial: ", changeAmount),
                detail("Custodial balance: ", custodialBalance),
                withdrawHint("Some returned change could not fit in your inventory or ender chest.")
        ), () -> queueNotification(
                player.getUniqueId(),
                PlayerNotificationType.CHANGE_ROUTED_TO_CUSTODIAL,
                changeAmount,
                custodialBalance,
                false
        ), "notify-change-routed");
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
        send(player, "Not Enough Room For Change", List.of(
                Component.text(
                        "This transaction was canceled because you do not have enough space in your inventory and/or ender chest to receive your change back.",
                        NamedTextColor.GRAY
                )
        ), () -> queueNotification(
                player.getUniqueId(),
                PlayerNotificationType.NOT_ENOUGH_ROOM_FOR_CHANGE,
                null,
                null,
                false
        ), "notify-not-enough-room-for-change");
    }

    public int deliverPendingNotifications(Player player) {
        if (player == null) {
            return 0;
        }
        List<PlayerNotificationRecord> queued = repository.listPlayerNotifications(player.getUniqueId());
        if (queued.isEmpty()) {
            return 0;
        }
        List<Component> rendered = queued.stream().map(this::render).toList();
        boolean delivered = schedulerService.runOnPlayerEntityScheduler(
                player,
                () -> rendered.forEach(player::sendMessage),
                "deliver-pending-notifications"
        );
        if (!delivered) {
            operationsLog.warn("Failed to deliver queued notifications to " + player.getUniqueId() + ". Leaving them queued.");
            return 0;
        }
        for (PlayerNotificationRecord notification : queued) {
            repository.deletePlayerNotification(notification.notificationId());
        }
        operationsLog.info("Delivered " + queued.size() + " queued notification(s) to " + player.getUniqueId() + ".");
        return queued.size();
    }

    private void send(Player player, Component message) {
        send(player, message, null, "send-notification");
    }

    private void send(Player player, String title, List<Component> details, Runnable onFailure, String operation) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("[EconomyPol] ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(title, NamedTextColor.YELLOW, TextDecoration.BOLD)));
        lines.addAll(details);
        send(player, Component.join(JoinConfiguration.separator(Component.newline()), lines), onFailure, operation);
    }

    private void send(Player player, Component message, Runnable onFailure, String operation) {
        boolean sent = schedulerService.runOnPlayerEntityScheduler(player, () -> player.sendMessage(message), operation);
        if (!sent && onFailure != null) {
            onFailure.run();
        }
    }

    private Component detail(String label, long amount) {
        return Component.text(label, NamedTextColor.GRAY)
                .append(Component.text(denominationService.format(amount), NamedTextColor.WHITE));
    }

    private Component withdrawHint(String lead) {
        return Component.text(lead + " Withdraw physical money with ", NamedTextColor.GRAY)
                .append(Component.text(WITHDRAW_COMMAND, NamedTextColor.YELLOW)
                        .clickEvent(ClickEvent.suggestCommand("/economypol withdraw "))
                        .hoverEvent(HoverEvent.showText(Component.text("Withdraw physical money from custodial storage.", NamedTextColor.YELLOW))))
                .append(Component.text(".", NamedTextColor.GRAY));
    }

    private Component render(PlayerNotificationRecord notification) {
        return switch (notification.notificationType()) {
            case INCOMING_OVERFLOW_TO_CUSTODIAL -> buildMessage(
                    "Incoming Money Routed to Custodial",
                    List.of(
                            detail("Moved to custodial: ", amount(notification.primaryAmount())),
                            detail("Custodial balance: ", amount(notification.secondaryAmount())),
                            withdrawHint("Some incoming money could not fit in your inventory or ender chest.")
                    )
            );
            case CUSTODIAL_WITHDRAWAL_RETAINED -> buildMessage(
                    "Withdraw Overflow Retained",
                    List.of(
                            detail("Retained in custodial: ", amount(notification.primaryAmount())),
                            detail("Custodial balance: ", amount(notification.secondaryAmount())),
                            withdrawHint("Some withdrawn money could not fit in your inventory or ender chest.")
                    )
            );
            case WALLET_OVERFLOW_TO_CUSTODIAL -> {
                List<Component> details = new ArrayList<>();
                details.add(detail("Moved to custodial: ", amount(notification.primaryAmount())));
                details.add(detail("Custodial balance: ", amount(notification.secondaryAmount())));
                if (notification.flagValue()) {
                    details.add(Component.text("Malformed money stacks were normalized before the overflow was stored.", NamedTextColor.GRAY));
                }
                details.add(withdrawHint("Some ender-wallet money could not be restored cleanly and was moved to custodial."));
                yield buildMessage("Ender Wallet Overflow", details);
            }
            case CUSTODIAL_BALANCE_REMINDER -> buildMessage(
                    "Custodial Balance Available",
                    List.of(
                            detail("Available in custodial: ", amount(notification.primaryAmount())),
                            withdrawHint("Withdraw it when you want to carry it as physical money.")
                    )
            );
            case OFFLINE_CREDIT_TO_CUSTODIAL -> buildMessage(
                    "Money Received While Offline",
                    List.of(
                            detail("Credited to custodial: ", amount(notification.primaryAmount())),
                            detail("Custodial balance: ", amount(notification.secondaryAmount())),
                            withdrawHint("Money received while you were offline was stored safely in custodial.")
                    )
            );
            case CHANGE_ROUTED_TO_CUSTODIAL -> buildMessage(
                    "Change Routed to Custodial",
                    List.of(
                            detail("Change moved to custodial: ", amount(notification.primaryAmount())),
                            detail("Custodial balance: ", amount(notification.secondaryAmount())),
                            withdrawHint("Some returned change could not fit in your inventory or ender chest.")
                    )
            );
            case NOT_ENOUGH_ROOM_FOR_CHANGE -> buildMessage(
                    "Not Enough Room For Change",
                    List.of(
                            Component.text(
                                    "This transaction was canceled because you do not have enough space in your inventory and/or ender chest to receive your change back.",
                                    NamedTextColor.GRAY
                            )
                    )
            );
        };
    }

    private Component buildMessage(String title, List<Component> details) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("[EconomyPol] ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text(title, NamedTextColor.YELLOW, TextDecoration.BOLD)));
        lines.addAll(details);
        return Component.join(JoinConfiguration.separator(Component.newline()), lines);
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
}
