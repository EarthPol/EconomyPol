package com.earthpol.economyPol.economy.logging;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.earthPolLib.logging.LogRetentionPolicy;
import com.earthpol.earthPolLib.logging.LogRetentionTask;

import java.util.concurrent.TimeUnit;

public final class EconomyLoggers {

    private final EnhancedLogger operations;
    private final EnhancedLogger audit;
    private final EnhancedLogger healthcheck;

    public EconomyLoggers(
            EnhancedLogger operations,
            EnhancedLogger audit,
            EnhancedLogger healthcheck
    ) {
        this.operations = operations;
        this.audit = audit;
        this.healthcheck = healthcheck;
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

    public void applyOperationsDebug(boolean debugEnabled) {
        operations.setDebugEnabled(debugEnabled);
    }

    public void applyRetentionPolicy(LogRetentionPolicy retentionPolicy) {
        applyRetentionPolicy(operations, retentionPolicy);
        applyRetentionPolicy(audit, retentionPolicy);
        applyRetentionPolicy(healthcheck, retentionPolicy);
    }

    public void close() {
        stopRetentionTask(healthcheck);
        stopRetentionTask(audit);
        stopRetentionTask(operations);
        healthcheck.close();
        audit.close();
        operations.close();
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
