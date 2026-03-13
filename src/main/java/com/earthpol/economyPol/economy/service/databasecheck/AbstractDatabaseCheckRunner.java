package com.earthpol.economyPol.economy.service.databasecheck;

import com.earthpol.earthPolLib.database.DatabaseService;
import com.earthpol.economyPol.economy.model.DatabaseCheckFinding;
import com.earthpol.economyPol.economy.model.DatabaseCheckReport;
import com.earthpol.economyPol.towny.TownyService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

abstract class AbstractDatabaseCheckRunner implements DatabaseCheckRunner {

    protected final DatabaseService databaseService;
    protected final TownyService townyService;

    protected AbstractDatabaseCheckRunner(DatabaseService databaseService, TownyService townyService) {
        this.databaseService = databaseService;
        this.townyService = townyService;
    }

    protected long queryLong(String sql) {
        try (Connection connection = databaseService.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return 0L;
            }
            return resultSet.getLong(1);
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to execute query: " + sql, exception);
        }
    }

    protected List<DatabaseCheckFinding> queryFindings(String sql, ResultSetMapper<DatabaseCheckFinding> mapper) {
        List<DatabaseCheckFinding> findings = new ArrayList<>();
        try (Connection connection = databaseService.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                findings.add(mapper.map(resultSet));
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to execute query: " + sql, exception);
        }
        return findings;
    }

    protected DatabaseCheckReport finish(
            Instant ranAt,
            long startedNanos,
            boolean healthy,
            String summary,
            Map<String, Long> statistics,
            List<String> notes,
            List<DatabaseCheckFinding> findings
    ) {
        long durationMillis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        return new DatabaseCheckReport(reportName(), ranAt, durationMillis, healthy, summary, statistics, notes, findings);
    }

    protected String nullableTimestamp(ResultSet resultSet, String columnName) throws SQLException {
        Timestamp timestamp = resultSet.getTimestamp(columnName);
        return timestamp == null ? "null" : timestamp.toInstant().toString();
    }

    @FunctionalInterface
    protected interface ResultSetMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }
}
