package com.earthpol.economyPol.economy.service.support;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class SchedulerService {

    private static final long PLAYER_ENTITY_SCHEDULER_TIMEOUT_SECONDS = 5L;

    private final Plugin plugin;
    private final EnhancedLogger operationsLog;

    public SchedulerService(Plugin plugin, EnhancedLogger operationsLog) {
        this.plugin = plugin;
        this.operationsLog = operationsLog;
    }

    public boolean isOwnedByCurrentRegion(Player player) {
        return player != null && Bukkit.isOwnedByCurrentRegion(player);
    }

    // This always targets the player's entity scheduler. It is not a location-based region task.
    public <T> Optional<T> callOnPlayerEntityScheduler(Player player, Supplier<T> action, String operation) {
        if (player == null) {
            return Optional.empty();
        }
        if (isOwnedByCurrentRegion(player)) {
            return Optional.ofNullable(action.get());
        }

        CompletableFuture<Optional<T>> future = new CompletableFuture<>();
        boolean scheduled = player.getScheduler().execute(
                plugin,
                () -> {
                    try {
                        future.complete(Optional.ofNullable(action.get()));
                    } catch (Throwable throwable) {
                        future.completeExceptionally(throwable);
                    }
                },
                () -> {
                    operationsLog.warn("Folia player entity-scheduler task retired before execution. operation=" + operation +
                            " player=" + player.getUniqueId());
                    future.complete(Optional.empty());
                },
                1L
        );
        if (!scheduled) {
            operationsLog.warn("Failed to schedule Folia player entity-scheduler task. operation=" + operation +
                    " player=" + player.getUniqueId());
            return Optional.empty();
        }
        try {
            return future.get(PLAYER_ENTITY_SCHEDULER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception exception) {
            operationsLog.warn("Timed out or failed waiting for Folia player entity-scheduler task. operation=" + operation +
                    " player=" + player.getUniqueId() + " error=" + exception.getClass().getSimpleName() +
                    ": " + exception.getMessage());
            return Optional.empty();
        }
    }

    public boolean runOnPlayerEntityScheduler(Player player, Runnable action, String operation) {
        return callOnPlayerEntityScheduler(player, () -> {
            action.run();
            return Boolean.TRUE;
        }, operation).isPresent();
    }

    public boolean runOnCommandSenderContext(CommandSender sender, Runnable action, String operation) {
        if (sender instanceof Player player) {
            return runOnPlayerEntityScheduler(player, action, operation);
        }
        try {
            Bukkit.getGlobalRegionScheduler().execute(plugin, action);
            return true;
        } catch (Throwable throwable) {
            operationsLog.severe("Failed to schedule command-sender callback. operation=" + operation +
                    " sender=" + sender.getName(), throwable);
            return false;
        }
    }

    public boolean runAsync(Runnable action, String operation) {
        try {
            Bukkit.getAsyncScheduler().runNow(plugin, task -> {
                try {
                    action.run();
                } catch (Throwable throwable) {
                    operationsLog.severe("Async scheduler task failed. operation=" + operation, throwable);
                }
            });
            return true;
        } catch (Throwable throwable) {
            operationsLog.severe("Failed to schedule async task. operation=" + operation, throwable);
            return false;
        }
    }
}


