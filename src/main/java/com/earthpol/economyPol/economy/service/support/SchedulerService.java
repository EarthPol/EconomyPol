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

/*
This class centralizes Folia scheduling. If there are folia issues, then the fix is probably here or caused
by the lack of use of this class.
 */
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
    // Use this when the caller needs the result immediately and is willing to block waiting for it.
    // That makes it appropriate for exact, synchronous player-state reads/writes, but risky across regions.
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

    // Blocking Runnable wrapper over callOnPlayerEntityScheduler(...).
    // Use this when the work must happen on the player scheduler and the caller needs an immediate success/failure answer,
    // but does not need to return a value from the scheduled work.
    public boolean runOnPlayerEntityScheduler(Player player, Runnable action, String operation) {
        return callOnPlayerEntityScheduler(player, () -> {
            action.run();
            return Boolean.TRUE;
        }, operation).isPresent();
    }

    // This also targets the player's entity scheduler, but it does not wait for a result.
    // Use this when the work must happen on the player scheduler but can complete asynchronously later.
    // This is the safer choice for cross-region fire-and-forget work like queued payment delivery.
    public boolean scheduleOnPlayerEntityScheduler(
            Player player,
            Runnable action,
            Runnable retiredAction,
            String operation
    ) {
        if (player == null) {
            return false;
        }
        if (isOwnedByCurrentRegion(player)) {
            try {
                action.run();
                return true;
            } catch (Throwable throwable) {
                operationsLog.severe("Player entity-scheduler task failed in owned region. operation=" + operation +
                        " player=" + player.getUniqueId(), throwable);
                return false;
            }
        }

        try {
            boolean scheduled = player.getScheduler().execute(
                    plugin,
                    () -> {
                        try {
                            action.run();
                        } catch (Throwable throwable) {
                            operationsLog.severe("Player entity-scheduler task failed. operation=" + operation +
                                    " player=" + player.getUniqueId(), throwable);
                        }
                    },
                    () -> {
                        operationsLog.warn("Folia player entity-scheduler task retired before execution. operation=" + operation +
                                " player=" + player.getUniqueId());
                        if (retiredAction != null) {
                            try {
                                retiredAction.run();
                            } catch (Throwable throwable) {
                                operationsLog.severe("Retired player entity-scheduler callback failed. operation=" + operation +
                                        " player=" + player.getUniqueId(), throwable);
                            }
                        }
                    },
                    1L
            );
            if (!scheduled) {
                operationsLog.warn("Failed to schedule Folia player entity-scheduler task. operation=" + operation +
                        " player=" + player.getUniqueId());
            }
            return scheduled;
        } catch (Throwable throwable) {
            operationsLog.severe("Failed to schedule player entity-scheduler task. operation=" + operation +
                    " player=" + player.getUniqueId(), throwable);
            return false;
        }
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

    public boolean runAsyncAtFixedRate(
            Runnable action,
            long initialDelay,
            long period,
            TimeUnit unit,
            String operation
    ) {
        try {
            Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task -> {
                try {
                    action.run();
                } catch (Throwable throwable) {
                    operationsLog.severe("Async fixed-rate task failed. operation=" + operation, throwable);
                }
            }, initialDelay, period, unit);
            return true;
        } catch (Throwable throwable) {
            operationsLog.severe("Failed to schedule async fixed-rate task. operation=" + operation, throwable);
            return false;
        }
    }
}
