package com.earthpol.economyPol.listener;

import com.earthpol.economyPol.service.EnderWalletService;
import com.earthpol.economyPol.service.EconomyService;
import com.earthpol.economyPol.service.NotificationService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerLifecycleListener implements Listener {

    private final EnderWalletService enderWalletService;
    private final EconomyService economyService;
    private final NotificationService notificationService;

    public PlayerLifecycleListener(
            EnderWalletService enderWalletService,
            EconomyService economyService,
            NotificationService notificationService
    ) {
        this.enderWalletService = enderWalletService;
        this.economyService = economyService;
        this.notificationService = notificationService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        long overflow = enderWalletService.syncSnapshotOnJoin(event.getPlayer());
        int deliveredNotifications = notificationService.deliverPendingNotifications(event.getPlayer());
        if (overflow <= 0L && deliveredNotifications == 0) {
            notificationService.notifyCustodialBalanceReminder(
                    event.getPlayer(),
                    economyService.getCustodialAvailable(event.getPlayer())
            );
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        enderWalletService.snapshotOnQuit(event.getPlayer());
    }
}
