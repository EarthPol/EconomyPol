package com.earthpol.economyPol.repository;

import com.earthpol.earthPolLib.database.DatabaseService;
import com.earthpol.earthPolLib.logging.EnhancedLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public abstract class AbstractRepositorySupport {

    private final DatabaseService databaseService;
    protected final EnhancedLogger operationsLog;
    protected final EnhancedLogger auditLog;

    protected AbstractRepositorySupport(DatabaseService databaseService, EnhancedLogger operationsLog, EnhancedLogger auditLog) {
        this.databaseService = databaseService;
        this.operationsLog = operationsLog;
        this.auditLog = auditLog;
    }

    protected <T> T inTransaction(SqlFunction<Connection, T> function) {
        try (Connection connection = databaseService.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = function.apply(connection);
                connection.commit();
                connection.setAutoCommit(previousAutoCommit);
                return result;
            } catch (Exception exception) {
                connection.rollback();
                connection.setAutoCommit(previousAutoCommit);
                throw exception;
            }
        } catch (Exception exception) {
            operationsLog.severe("Database transaction failed.", exception);
            throw new RuntimeException(exception);
        }
    }

    protected void update(String sql, Object... parameters) {
        try (Connection connection = databaseService.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        } catch (SQLException exception) {
            operationsLog.severe("Database update failed: " + sql, exception);
            throw new RuntimeException(exception);
        }
    }

    protected int updateCount(String sql, Object... parameters) {
        try (Connection connection = databaseService.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            operationsLog.severe("Database update failed: " + sql, exception);
            throw new RuntimeException(exception);
        }
    }

    protected <T> Optional<T> queryOne(String sql, SqlConsumer<PreparedStatement> binder, SqlFunction<ResultSet, T> mapper) {
        try (Connection connection = databaseService.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.accept(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapper.apply(resultSet));
            }
        } catch (Exception exception) {
            operationsLog.severe("Database query failed: " + sql, exception);
            throw new RuntimeException(exception);
        }
    }

    protected <T> List<T> queryList(String sql, SqlConsumer<PreparedStatement> binder, SqlFunction<ResultSet, T> mapper) {
        List<T> results = new ArrayList<>();
        try (Connection connection = databaseService.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.accept(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    results.add(mapper.apply(resultSet));
                }
            }
        } catch (Exception exception) {
            operationsLog.severe("Database list query failed: " + sql, exception);
            throw new RuntimeException(exception);
        }
        return results;
    }

    protected void bind(PreparedStatement statement, Object... parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            Object parameter = parameters[index];
            int jdbcIndex = index + 1;
            if (parameter == null) {
                statement.setNull(jdbcIndex, java.sql.Types.VARCHAR);
            } else if (parameter instanceof String string) {
                statement.setString(jdbcIndex, string);
            } else if (parameter instanceof Boolean bool) {
                statement.setBoolean(jdbcIndex, bool);
            } else if (parameter instanceof Integer integer) {
                statement.setInt(jdbcIndex, integer);
            } else if (parameter instanceof Long longValue) {
                statement.setLong(jdbcIndex, longValue);
            } else {
                statement.setObject(jdbcIndex, parameter);
            }
        }
    }

    protected int deleteWhere(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        }
    }

    protected static String uuid(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }

    protected static UUID parseUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    protected static Long nullableLong(ResultSet resultSet, String columnName) throws SQLException {
        long value = resultSet.getLong(columnName);
        return resultSet.wasNull() ? null : value;
    }

    @FunctionalInterface
    protected interface SqlFunction<T, R> {
        R apply(T value) throws Exception;
    }

    @FunctionalInterface
    protected interface SqlConsumer<T> {
        void accept(T value) throws Exception;
    }
}
