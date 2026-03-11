package com.earthpol.economyPol.listener;

import com.earthpol.economyPol.service.PlayerMoneyLockService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;

public final class EnderChestLockListener implements Listener {

    private final PlayerMoneyLockService playerMoneyLockService;

    public EnderChestLockListener(PlayerMoneyLockService playerMoneyLockService) {
        this.playerMoneyLockService = playerMoneyLockService;
    }

    // TODO: Test if this clashes with plugin-based inventory open such as essentials /ec command
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player &&
                event.getInventory().getType() == InventoryType.ENDER_CHEST &&
                playerMoneyLockService.isLocked(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage("Your money is syncing. Try again in a moment.");
        }
    }
}
