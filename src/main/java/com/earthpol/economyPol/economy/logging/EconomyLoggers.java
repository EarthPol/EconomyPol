package com.earthpol.economyPol.economy.logging;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.earthPolLib.logging.LogRetentionPolicy;
import com.earthpol.earthPolLib.logging.LogRetentionTask;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class EconomyLoggers {

    public enum LogType {
        OPERATIONS,
        AUDIT,
        HEALTHCHECK,
        MAIN;
    }

    private final EnhancedLogger operations;
    private final EnhancedLogger audit;
    private final EnhancedLogger healthcheck;
    private final EnhancedLogger main;

    private final Plugin plugin;

    public EconomyLoggers(
            EnhancedLogger operations,
            EnhancedLogger audit,
            EnhancedLogger healthcheck,
            Plugin plugin
    ) {
        this.operations = operations;
        this.audit = audit;
        this.healthcheck = healthcheck;

        this.plugin = plugin;
        this.main = EnhancedLogger.create(plugin,"main");
        main.enableConsoleLogger(false);
    }

    public EnhancedLogger operations() {
        return operations;
    }

    public EnhancedLogger audit() {
        return audit;
    }

    public EnhancedLogger healthcheck() {
        return healthcheck;
    }

    public EnhancedLogger main() {
        return main;
    }

    public void applyRetentionPolicy(LogRetentionPolicy retentionPolicy) {
        applyRetentionPolicy(main, retentionPolicy);
        applyRetentionPolicy(operations, retentionPolicy);
        applyRetentionPolicy(audit, retentionPolicy);
        applyRetentionPolicy(healthcheck, retentionPolicy);
    }

    public void applyConsoleLogging(boolean enabled) {
        operations.enableConsoleLogger(enabled);
        audit.enableConsoleLogger(enabled);
        healthcheck.enableConsoleLogger(enabled);
        main.enableConsoleLogger(false);
    }

    public void close() {
        stopRetentionTask(healthcheck);
        stopRetentionTask(audit);
        stopRetentionTask(operations);
        stopRetentionTask(main);
        healthcheck.close();
        audit.close();
        operations.close();
        main.close();
    }

    public void log(String message, LogType type) {
        main.info(message);
        switch (type) {
            case OPERATIONS -> operations.info(message);
            case AUDIT -> audit.info(message);
            case HEALTHCHECK -> healthcheck.info(message);
            default -> {}
        }
    }
    public void logWarn(String message, LogType type) {
        main.warn(message);
        switch (type) {
            case OPERATIONS -> operations.warn(message);
            case AUDIT -> audit.warn(message);
            case HEALTHCHECK -> healthcheck.warn(message);
            default -> {}
        }
    }
    public void logSevere(String message, LogType type, Throwable throwable) {
        main.severe(message, throwable);
        switch (type) {
            case OPERATIONS -> operations.severe(message, throwable);
            case AUDIT -> audit.severe(message, throwable);
            case HEALTHCHECK -> healthcheck.severe(message, throwable);
            default -> {}
        }
    }
    public void logSevere(String message, LogType type) {
        main.severe(message);
        switch (type) {
            case OPERATIONS -> operations.severe(message);
            case AUDIT -> audit.severe(message);
            case HEALTHCHECK -> healthcheck.severe(message);
            default -> {}
        }
    }

    public String playerContext(Player player) {
        if (player == null) {
            return "player=null";
        }
        return playerContext(player.getUniqueId(), player.getName());
    }

    public String playerContext(OfflinePlayer player) {
        if (player == null) {
            return "player=null";
        }
        return playerContext(player.getUniqueId(), player.getName());
    }

    public String playerContext(UUID playerUuid) {
        if (playerUuid == null) {
            return "player=null";
        }
        Player onlinePlayer = Bukkit.getPlayer(playerUuid);
        if (onlinePlayer != null) {
            return playerContext(playerUuid, onlinePlayer.getName());
        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerUuid);
        return playerContext(playerUuid, offlinePlayer == null ? null : offlinePlayer.getName());
    }

    public String playerContext(UUID playerUuid, String playerUsername) {
        if (playerUuid == null) {
            return "player=null";
        }
        StringBuilder builder = new StringBuilder("player=").append(playerUuid);
        if (playerUsername != null && !playerUsername.isBlank()) {
            builder.append(" player_username=").append(playerUsername);
        }
        return builder.toString();
    }

    private static void applyRetentionPolicy(EnhancedLogger logger, LogRetentionPolicy retentionPolicy) {
        stopRetentionTask(logger);
        LogRetentionTask task = new LogRetentionTask(
                retentionPolicy,
                1,
                TimeUnit.DAYS,
                logger
        );
        logger.setLogRetentionTask(task);
        task.startNow();
    }

    private static void stopRetentionTask(EnhancedLogger logger) {
        LogRetentionTask existingTask = logger.getLogRetentionTask();
        if (existingTask != null) {
            existingTask.stop();
        }
    }
}
