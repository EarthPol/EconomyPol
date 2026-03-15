package com.earthpol.economyPol.listener;

import com.earthpol.economyPol.economy.listener.EnderChestLockListener;
import com.earthpol.economyPol.economy.service.player.PlayerMoneyLockService;
import org.bukkit.Bukkit;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class EnderChestLockListenerTest {

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
    void pluginOpenedEnderChestIsCancelledWhilePlayerIsLocked() {
        PlayerMock player = server.addPlayer();
        PlayerMoneyLockService lockService = new PlayerMoneyLockService();
        lockService.lock(player.getUniqueId());
        EnderChestLockListener listener = new EnderChestLockListener(lockService);

        Inventory pluginOpenedEnderChest = Bukkit.createInventory(null, InventoryType.ENDER_CHEST);
        InventoryOpenEvent event = mock(InventoryOpenEvent.class);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        when(event.getPlayer()).thenReturn(player);
        when(event.getInventory()).thenReturn(pluginOpenedEnderChest);
        doAnswer(invocation -> {
            cancelled.set(invocation.getArgument(0));
            return null;
        }).when(event).setCancelled(org.mockito.ArgumentMatchers.anyBoolean());

        listener.onInventoryOpen(event);

        assertTrue(cancelled.get());
        assertEquals("Your money is syncing. Try again in a moment.", player.nextMessage());
    }

    @Test
    void nonEnderChestInventoryIsNotCancelledWhilePlayerIsLocked() {
        PlayerMock player = server.addPlayer();
        PlayerMoneyLockService lockService = new PlayerMoneyLockService();
        lockService.lock(player.getUniqueId());
        EnderChestLockListener listener = new EnderChestLockListener(lockService);

        Inventory chest = Bukkit.createInventory(null, InventoryType.CHEST);
        InventoryOpenEvent event = mock(InventoryOpenEvent.class);
        AtomicBoolean cancelled = new AtomicBoolean(false);

        when(event.getPlayer()).thenReturn(player);
        when(event.getInventory()).thenReturn(chest);
        doAnswer(invocation -> {
            cancelled.set(invocation.getArgument(0));
            return null;
        }).when(event).setCancelled(org.mockito.ArgumentMatchers.anyBoolean());

        listener.onInventoryOpen(event);

        assertFalse(cancelled.get());
        assertTrue(player.nextMessage() == null);
    }
}
