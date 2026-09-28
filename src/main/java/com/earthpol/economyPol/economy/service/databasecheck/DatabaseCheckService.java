package com.earthpol.economyPol.economy.service.databasecheck;

import com.earthpol.earthpollib.database.DatabaseService;
import com.earthpol.economyPol.economy.model.DatabaseCheckFinding;
import com.earthpol.economyPol.economy.model.DatabaseCheckReport;
import com.earthpol.economyPol.towny.TownyService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DatabaseCheckService {

    private final Map<String, DatabaseCheckRunner> runners;

    public DatabaseCheckService(DatabaseService databaseService, TownyService townyService) {
        this.runners = registerRunners(
                new AccountsDatabaseCheckRunner(databaseService, townyService),
                new BalancesDatabaseCheckRunner(databaseService, townyService),
                new SnapshotsDatabaseCheckRunner(databaseService, townyService),
                new UncleanSnapshotsDatabaseCheckRunner(databaseService, townyService),
                new ReservationsDatabaseCheckRunner(databaseService, townyService),
                new NotificationsDatabaseCheckRunner(databaseService, townyService),
                new TownyAccountsDatabaseCheckRunner(databaseService, townyService),
                new StatsDatabaseCheckRunner(databaseService, townyService)
        );
    }

    public List<String> availableReports() {
        return List.copyOf(runners.keySet());
    }

    public DatabaseCheckReport runReport(String reportName) {
        String normalized = reportName == null ? "" : reportName.trim().toLowerCase();
        Instant ranAt = Instant.now();
        long startedNanos = System.nanoTime();
        DatabaseCheckRunner runner = runners.get(normalized);
        if (runner == null) {
            return finishUnknownReport(normalized.isBlank() ? "unknown" : normalized, reportName, ranAt, startedNanos);
        }
        try {
            return runner.run(ranAt, startedNanos);
        } catch (Exception exception) {
            return finishExecutionFailure(runner.reportName(), ranAt, startedNanos, exception);
        }
    }

    private Map<String, DatabaseCheckRunner> registerRunners(DatabaseCheckRunner... reportRunners) {
        Map<String, DatabaseCheckRunner> orderedRunners = new LinkedHashMap<>();
        for (DatabaseCheckRunner runner : reportRunners) {
            orderedRunners.put(runner.reportName(), runner);
        }
        return Map.copyOf(orderedRunners);
    }

    private DatabaseCheckReport finishUnknownReport(String normalized, String rawReportName, Instant ranAt, long startedNanos) {
        return finish(
                normalized,
                ranAt,
                startedNanos,
                false,
                "Unknown report alias. Available reports: " + String.join(", ", availableReports()) + ".",
                Map.of(),
                List.of(),
                List.of(new DatabaseCheckFinding("SYSTEM", "report=" + rawReportName, "Unsupported database check alias"))
        );
    }

    private DatabaseCheckReport finishExecutionFailure(String reportName, Instant ranAt, long startedNanos, Exception exception) {
        return finish(
                reportName,
                ranAt,
                startedNanos,
                false,
                "Database check failed with an exception.",
                Map.of(),
                List.of(exception.getClass().getSimpleName() + ": " + exception.getMessage()),
                List.of(new DatabaseCheckFinding("SYSTEM", "report=" + reportName, "Check execution error"))
        );
    }

    private DatabaseCheckReport finish(
            String reportName,
            Instant ranAt,
            long startedNanos,
            boolean healthy,
            String summary,
            Map<String, Long> statistics,
            List<String> notes,
            List<DatabaseCheckFinding> findings
    ) {
        long durationMillis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        return new DatabaseCheckReport(reportName, ranAt, durationMillis, healthy, summary, statistics, notes, findings);
    }
}
