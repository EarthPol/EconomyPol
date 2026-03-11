package com.earthpol.economyPol.persistence;

import com.earthpol.earthPolLib.database.DatabaseService;
import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.domain.AccountRecord;
import com.earthpol.economyPol.domain.AccountType;
import com.earthpol.economyPol.domain.BalanceRecord;
import com.earthpol.economyPol.domain.EnderWalletSnapshot;
import com.earthpol.economyPol.domain.OfflineEnderWalletState;
import com.earthpol.economyPol.domain.PlayerAccountPolicy;
import com.earthpol.economyPol.domain.PlayerNotificationRecord;
import com.earthpol.economyPol.domain.PlayerNotificationType;
import com.earthpol.economyPol.domain.ReservationRecord;
import com.earthpol.economyPol.domain.ReservationStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class JdbcEconomyRepository {

    private final DatabaseService databaseService;
    private final EnhancedLogger operationsLog;
    private final EnhancedLogger auditLog;

    public JdbcEconomyRepository(DatabaseService databaseService, EnhancedLogger operationsLog, EnhancedLogger auditLog) {
        this.databaseService = databaseService;
        this.operationsLog = operationsLog;
        this.auditLog = auditLog;
    }

    public AccountRecord ensurePlayerAccount(UUID playerUuid, String playerName, PlayerAccountPolicy policy) {
        long now = System.currentTimeMillis();
        update("""
                INSERT INTO economy_accounts (
                    account_id, account_type, owner_uuid, account_name,
                    allow_self_deposit, allow_external_credit, allow_self_withdraw,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    account_name = VALUES(account_name),
                    allow_self_deposit = VALUES(allow_self_deposit),
                    allow_external_credit = VALUES(allow_external_credit),
                    allow_self_withdraw = VALUES(allow_self_withdraw),
                    updated_at = VALUES(updated_at)
                """,
                uuid(playerUuid),
                AccountType.PLAYER.name(),
                uuid(playerUuid),
                playerName == null ? uuid(playerUuid) : playerName,
                policy.allowSelfDeposit(),
                policy.allowExternalCredit(),
                policy.allowSelfWithdraw(),
                now,
                now
        );
        ensureBalanceRow(playerUuid);
        return new AccountRecord(playerUuid, AccountType.PLAYER, playerUuid, playerName == null ? uuid(playerUuid) : playerName, policy);
    }

    public AccountRecord ensureSharedAccount(String name, UUID ownerUuid) {
        UUID accountId = UUID.nameUUIDFromBytes(("shared:" + name.toLowerCase()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return ensureSharedAccount(accountId, name, ownerUuid);
    }

    public AccountRecord ensureSharedAccount(UUID accountId, String name, UUID ownerUuid) {
        long now = System.currentTimeMillis();
        update("""
                INSERT INTO economy_accounts (
                    account_id, account_type, owner_uuid, account_name,
                    allow_self_deposit, allow_external_credit, allow_self_withdraw,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    owner_uuid = VALUES(owner_uuid),
                    updated_at = VALUES(updated_at)
                """,
                uuid(accountId),
                AccountType.SHARED.name(),
                uuid(ownerUuid),
                name,
                true,
                true,
                true,
                now,
                now
        );
        ensureBalanceRow(accountId);
        return new AccountRecord(accountId, AccountType.SHARED, ownerUuid, name, new PlayerAccountPolicy(true, true, true));
    }

    public Optional<AccountRecord> findAccount(UUID accountId) {
        return queryOne("""
                SELECT account_id, account_type, owner_uuid, account_name,
                       allow_self_deposit, allow_external_credit, allow_self_withdraw
                FROM economy_accounts
                WHERE account_id = ?
                """,
                statement -> statement.setString(1, uuid(accountId)),
                this::readAccount
        );
    }

    public Optional<AccountRecord> findPlayerAccount(UUID playerUuid) {
        return queryOne("""
                SELECT account_id, account_type, owner_uuid, account_name,
                       allow_self_deposit, allow_external_credit, allow_self_withdraw
                FROM economy_accounts
                WHERE owner_uuid = ? AND account_type = ?
                """,
                statement -> {
                    statement.setString(1, uuid(playerUuid));
                    statement.setString(2, AccountType.PLAYER.name());
                },
                this::readAccount
        );
    }

    public Optional<AccountRecord> findSharedAccount(String name) {
        return queryOne("""
                SELECT account_id, account_type, owner_uuid, account_name,
                       allow_self_deposit, allow_external_credit, allow_self_withdraw
                FROM economy_accounts
                WHERE account_name = ? AND account_type = ?
                """,
                statement -> {
                    statement.setString(1, name);
                    statement.setString(2, AccountType.SHARED.name());
                },
                this::readAccount
        );
    }

    public Optional<AccountRecord> findSharedAccount(UUID accountId) {
        return queryOne("""
                SELECT account_id, account_type, owner_uuid, account_name,
                       allow_self_deposit, allow_external_credit, allow_self_withdraw
                FROM economy_accounts
                WHERE account_id = ? AND account_type = ?
                """,
                statement -> {
                    statement.setString(1, uuid(accountId));
                    statement.setString(2, AccountType.SHARED.name());
                },
                this::readAccount
        );
    }

    public Optional<AccountRecord> findAccountByName(String name) {
        return queryOne("""
                SELECT account_id, account_type, owner_uuid, account_name,
                       allow_self_deposit, allow_external_credit, allow_self_withdraw
                FROM economy_accounts
                WHERE account_name = ?
                """,
                statement -> statement.setString(1, name),
                this::readAccount
        );
    }

    public List<String> listSharedAccountNames() {
        return queryList(
                "SELECT account_name FROM economy_accounts WHERE account_type = ? ORDER BY account_name ASC",
                statement -> statement.setString(1, AccountType.SHARED.name()),
                resultSet -> resultSet.getString(1)
        );
    }

    public Map<UUID, String> listAccountNames() {
        Map<UUID, String> names = new LinkedHashMap<>();
        queryList(
                "SELECT account_id, account_name FROM economy_accounts ORDER BY account_name ASC",
                statement -> {
                },
                resultSet -> {
                    names.put(parseUuid(resultSet.getString("account_id")), resultSet.getString("account_name"));
                    return null;
                }
        );
        return Map.copyOf(names);
    }

    public BalanceRecord getBalance(UUID accountId) {
        ensureBalanceRow(accountId);
        return queryOne("""
                SELECT available_balance, reserved_balance
                FROM economy_balances
                WHERE account_id = ?
                """,
                statement -> statement.setString(1, uuid(accountId)),
                resultSet -> new BalanceRecord(resultSet.getLong("available_balance"), resultSet.getLong("reserved_balance"))
        ).orElse(new BalanceRecord(0L, 0L));
    }

    public BalanceRecord changeAvailable(
            UUID accountId,
            long delta,
            String entryType,
            String reason,
            UUID playerUuid,
            UUID relatedAccountId
    ) {
        return inTransaction(connection -> {
            BalanceRecord current = selectBalanceForUpdate(connection, accountId);
            long nextAvailable = current.availableBalance() + delta;
            if (nextAvailable < 0L) {
                throw new IllegalStateException("Insufficient available balance for account " + accountId);
            }
            updateBalance(connection, accountId, nextAvailable, current.reservedBalance());
            insertLedger(connection, accountId, relatedAccountId, playerUuid, delta, nextAvailable, current.reservedBalance(), entryType, reason);
            auditLog.info("balance-change account=" + accountId + " delta=" + delta + " available=" + nextAvailable +
                    " reserved=" + current.reservedBalance() + " type=" + entryType + " reason=" + reason);
            return new BalanceRecord(nextAvailable, current.reservedBalance());
        });
    }

    public BalanceRecord reserveAvailable(UUID accountId, long amount, String reason) {
        return inTransaction(connection -> {
            BalanceRecord current = selectBalanceForUpdate(connection, accountId);
            if (current.availableBalance() < amount) {
                throw new IllegalStateException("Insufficient available balance to reserve.");
            }
            long nextAvailable = current.availableBalance() - amount;
            long nextReserved = current.reservedBalance() + amount;
            updateBalance(connection, accountId, nextAvailable, nextReserved);
            insertLedger(connection, accountId, null, null, -amount, nextAvailable, nextReserved, "RESERVE", reason);
            return new BalanceRecord(nextAvailable, nextReserved);
        });
    }

    public BalanceRecord releaseReserved(UUID accountId, long amount, String reason) {
        return inTransaction(connection -> {
            BalanceRecord current = selectBalanceForUpdate(connection, accountId);
            if (current.reservedBalance() < amount) {
                throw new IllegalStateException("Insufficient reserved balance to release.");
            }
            long nextAvailable = current.availableBalance() + amount;
            long nextReserved = current.reservedBalance() - amount;
            updateBalance(connection, accountId, nextAvailable, nextReserved);
            insertLedger(connection, accountId, null, null, amount, nextAvailable, nextReserved, "RESERVATION_RELEASE", reason);
            return new BalanceRecord(nextAvailable, nextReserved);
        });
    }

    public BalanceRecord captureReserved(UUID accountId, long amount, String reason) {
        return inTransaction(connection -> {
            BalanceRecord current = selectBalanceForUpdate(connection, accountId);
            if (current.reservedBalance() < amount) {
                throw new IllegalStateException("Insufficient reserved balance to capture.");
            }
            long nextReserved = current.reservedBalance() - amount;
            updateBalance(connection, accountId, current.availableBalance(), nextReserved);
            insertLedger(connection, accountId, null, null, -amount, current.availableBalance(), nextReserved, "RESERVATION_CAPTURE", reason);
            return new BalanceRecord(current.availableBalance(), nextReserved);
        });
    }

    public Optional<EnderWalletSnapshot> findEnderWalletSnapshot(UUID playerUuid) {
        return queryOne("""
                SELECT player_uuid, base_units, state, last_clean_sync_at
                FROM economy_ender_wallet_snapshots
                WHERE player_uuid = ?
                """,
                statement -> statement.setString(1, uuid(playerUuid)),
                resultSet -> new EnderWalletSnapshot(
                        parseUuid(resultSet.getString("player_uuid")),
                        resultSet.getLong("base_units"),
                        OfflineEnderWalletState.valueOf(resultSet.getString("state")),
                        nullableLong(resultSet, "last_clean_sync_at")
                )
        );
    }

    public void upsertEnderWalletSnapshot(EnderWalletSnapshot snapshot) {
        update("""
                INSERT INTO economy_ender_wallet_snapshots (player_uuid, base_units, state, last_clean_sync_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    base_units = VALUES(base_units),
                    state = VALUES(state),
                    last_clean_sync_at = VALUES(last_clean_sync_at),
                    updated_at = VALUES(updated_at)
                """,
                uuid(snapshot.playerUuid()),
                snapshot.baseUnits(),
                snapshot.state().name(),
                snapshot.lastCleanSyncAt(),
                System.currentTimeMillis()
        );
    }

    public void deleteEnderWalletSnapshot(UUID playerUuid) {
        update("DELETE FROM economy_ender_wallet_snapshots WHERE player_uuid = ?", uuid(playerUuid));
    }

    public void markStaleSnapshotsDisabled() {
        update("""
                UPDATE economy_ender_wallet_snapshots
                SET state = ?, updated_at = ?
                WHERE state = ?
                """,
                OfflineEnderWalletState.DISABLED_UNCLEAN.name(),
                System.currentTimeMillis(),
                OfflineEnderWalletState.SYNCING.name()
        );
    }

    public ReservationRecord createReservation(UUID accountId, long amount, String reason, Long expiresAt) {
        UUID reservationId = UUID.randomUUID();
        update("""
                INSERT INTO economy_reservations (reservation_id, account_id, amount, status, reason, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                uuid(reservationId),
                uuid(accountId),
                amount,
                ReservationStatus.ACTIVE.name(),
                reason,
                System.currentTimeMillis(),
                expiresAt
        );
        auditLog.info("reservation-create id=" + reservationId + " account=" + accountId + " amount=" + amount + " reason=" + reason);
        return new ReservationRecord(reservationId, accountId, amount, ReservationStatus.ACTIVE, reason, expiresAt);
    }

    public Optional<ReservationRecord> findReservation(UUID reservationId) {
        return queryOne("""
                SELECT reservation_id, account_id, amount, status, reason, expires_at
                FROM economy_reservations
                WHERE reservation_id = ?
                """,
                statement -> statement.setString(1, uuid(reservationId)),
                resultSet -> new ReservationRecord(
                        parseUuid(resultSet.getString("reservation_id")),
                        parseUuid(resultSet.getString("account_id")),
                        resultSet.getLong("amount"),
                        ReservationStatus.valueOf(resultSet.getString("status")),
                        resultSet.getString("reason"),
                        nullableLong(resultSet, "expires_at")
                )
        );
    }

    public void updateReservationStatus(UUID reservationId, ReservationStatus status) {
        update("UPDATE economy_reservations SET status = ? WHERE reservation_id = ?", status.name(), uuid(reservationId));
        auditLog.info("reservation-status id=" + reservationId + " status=" + status);
    }

    public void createPlayerNotification(
            UUID playerUuid,
            PlayerNotificationType notificationType,
            Long primaryAmount,
            Long secondaryAmount,
            String detailText,
            boolean flagValue
    ) {
        update("""
                INSERT INTO economy_player_notifications (
                    player_uuid, notification_type, primary_amount, secondary_amount, detail_text, flag_value, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                uuid(playerUuid),
                notificationType.name(),
                primaryAmount,
                secondaryAmount,
                detailText,
                flagValue,
                System.currentTimeMillis()
        );
        auditLog.info("player-notification-create player=" + playerUuid + " type=" + notificationType);
    }

    public List<PlayerNotificationRecord> listPlayerNotifications(UUID playerUuid) {
        return queryList("""
                SELECT notification_id, player_uuid, notification_type, primary_amount, secondary_amount, detail_text, flag_value, created_at
                FROM economy_player_notifications
                WHERE player_uuid = ?
                ORDER BY created_at ASC, notification_id ASC
                """,
                statement -> statement.setString(1, uuid(playerUuid)),
                resultSet -> new PlayerNotificationRecord(
                        resultSet.getLong("notification_id"),
                        parseUuid(resultSet.getString("player_uuid")),
                        PlayerNotificationType.valueOf(resultSet.getString("notification_type")),
                        nullableLong(resultSet, "primary_amount"),
                        nullableLong(resultSet, "secondary_amount"),
                        resultSet.getString("detail_text"),
                        resultSet.getBoolean("flag_value"),
                        resultSet.getLong("created_at")
                )
        );
    }

    public int deletePlayerNotification(long notificationId) {
        return updateCount("DELETE FROM economy_player_notifications WHERE notification_id = ?", notificationId);
    }

    public boolean renameAccount(UUID accountId, String newName) {
        return updateCount(
                "UPDATE economy_accounts SET account_name = ?, updated_at = ? WHERE account_id = ?",
                newName,
                System.currentTimeMillis(),
                uuid(accountId)
        ) > 0;
    }

    public boolean updateSharedAccountOwner(UUID accountId, UUID ownerUuid) {
        return updateCount(
                "UPDATE economy_accounts SET owner_uuid = ?, updated_at = ? WHERE account_id = ? AND account_type = ?",
                uuid(ownerUuid),
                System.currentTimeMillis(),
                uuid(accountId),
                AccountType.SHARED.name()
        ) > 0;
    }

    public boolean deleteSharedAccount(UUID accountId) {
        return inTransaction(connection -> {
            deleteWhere(connection, "DELETE FROM economy_account_members WHERE account_id = ?", uuid(accountId));
            deleteWhere(connection, "DELETE FROM economy_reservations WHERE account_id = ?", uuid(accountId));
            deleteWhere(connection, "DELETE FROM economy_balances WHERE account_id = ?", uuid(accountId));
            deleteWhere(connection, "DELETE FROM economy_ledger_entries WHERE account_id = ? OR related_account_id = ?", uuid(accountId), uuid(accountId));
            int deletedAccounts = deleteWhere(connection,
                    "DELETE FROM economy_accounts WHERE account_id = ? AND account_type = ?",
                    uuid(accountId),
                    AccountType.SHARED.name());
            return deletedAccounts > 0;
        });
    }

    public void upsertAccountMember(UUID accountId, UUID memberUuid, String role) {
        update("""
                INSERT INTO economy_account_members (account_id, member_uuid, membership_role, created_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE membership_role = VALUES(membership_role)
                """,
                uuid(accountId),
                uuid(memberUuid),
                role,
                System.currentTimeMillis()
        );
    }

    public boolean removeAccountMember(UUID accountId, UUID memberUuid) {
        return updateCount(
                "DELETE FROM economy_account_members WHERE account_id = ? AND member_uuid = ?",
                uuid(accountId),
                uuid(memberUuid)
        ) > 0;
    }

    public Optional<String> findAccountMemberRole(UUID accountId, UUID memberUuid) {
        return queryOne("""
                SELECT membership_role
                FROM economy_account_members
                WHERE account_id = ? AND member_uuid = ?
                """,
                statement -> {
                    statement.setString(1, uuid(accountId));
                    statement.setString(2, uuid(memberUuid));
                },
                resultSet -> resultSet.getString("membership_role")
        );
    }

    private AccountRecord readAccount(ResultSet resultSet) throws SQLException {
        return new AccountRecord(
                parseUuid(resultSet.getString("account_id")),
                AccountType.valueOf(resultSet.getString("account_type")),
                parseUuid(resultSet.getString("owner_uuid")),
                resultSet.getString("account_name"),
                new PlayerAccountPolicy(
                        resultSet.getBoolean("allow_self_deposit"),
                        resultSet.getBoolean("allow_external_credit"),
                        resultSet.getBoolean("allow_self_withdraw")
                )
        );
    }

    private void ensureBalanceRow(UUID accountId) {
        update("""
                INSERT INTO economy_balances (account_id, available_balance, reserved_balance, updated_at)
                VALUES (?, 0, 0, ?)
                ON DUPLICATE KEY UPDATE updated_at = updated_at
                """,
                uuid(accountId),
                System.currentTimeMillis()
        );
    }

    private BalanceRecord selectBalanceForUpdate(Connection connection, UUID accountId) throws SQLException {
        ensureBalanceRow(connection, accountId);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT available_balance, reserved_balance
                FROM economy_balances
                WHERE account_id = ?
                FOR UPDATE
                """)) {
            statement.setString(1, uuid(accountId));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new BalanceRecord(0L, 0L);
                }
                return new BalanceRecord(resultSet.getLong("available_balance"), resultSet.getLong("reserved_balance"));
            }
        }
    }

    private void ensureBalanceRow(Connection connection, UUID accountId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economy_balances (account_id, available_balance, reserved_balance, updated_at)
                VALUES (?, 0, 0, ?)
                ON DUPLICATE KEY UPDATE updated_at = updated_at
                """)) {
            statement.setString(1, uuid(accountId));
            statement.setLong(2, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private void updateBalance(Connection connection, UUID accountId, long available, long reserved) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE economy_balances
                SET available_balance = ?, reserved_balance = ?, updated_at = ?
                WHERE account_id = ?
                """)) {
            statement.setLong(1, available);
            statement.setLong(2, reserved);
            statement.setLong(3, System.currentTimeMillis());
            statement.setString(4, uuid(accountId));
            statement.executeUpdate();
        }
    }

    private void insertLedger(
            Connection connection,
            UUID accountId,
            UUID relatedAccountId,
            UUID playerUuid,
            long delta,
            Long available,
            Long reserved,
            String entryType,
            String reason
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economy_ledger_entries (
                    account_id, related_account_id, player_uuid,
                    delta, available_balance, reserved_balance, entry_type, reason, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, uuid(accountId));
            statement.setString(2, uuid(relatedAccountId));
            statement.setString(3, uuid(playerUuid));
            statement.setLong(4, delta);
            if (available == null) {
                statement.setNull(5, java.sql.Types.BIGINT);
            } else {
                statement.setLong(5, available);
            }
            if (reserved == null) {
                statement.setNull(6, java.sql.Types.BIGINT);
            } else {
                statement.setLong(6, reserved);
            }
            statement.setString(7, entryType);
            statement.setString(8, reason);
            statement.setLong(9, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    private <T> T inTransaction(SqlFunction<Connection, T> function) {
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

    private void update(String sql, Object... parameters) {
        try (Connection connection = databaseService.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            statement.executeUpdate();
        } catch (SQLException exception) {
            operationsLog.severe("Database update failed: " + sql, exception);
            throw new RuntimeException(exception);
        }
    }

    private int updateCount(String sql, Object... parameters) {
        try (Connection connection = databaseService.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            operationsLog.severe("Database update failed: " + sql, exception);
            throw new RuntimeException(exception);
        }
    }

    private <T> Optional<T> queryOne(String sql, SqlConsumer<PreparedStatement> binder, SqlFunction<ResultSet, T> mapper) {
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

    private <T> List<T> queryList(String sql, SqlConsumer<PreparedStatement> binder, SqlFunction<ResultSet, T> mapper) {
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

    private void bind(PreparedStatement statement, Object... parameters) throws SQLException {
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

    private int deleteWhere(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, parameters);
            return statement.executeUpdate();
        }
    }

    private static String uuid(UUID uuid) {
        return uuid == null ? null : uuid.toString();
    }

    private static UUID parseUuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static Long nullableLong(ResultSet resultSet, String columnName) throws SQLException {
        long value = resultSet.getLong(columnName);
        return resultSet.wasNull() ? null : value;
    }

    @FunctionalInterface
    private interface SqlFunction<T, R> {
        R apply(T value) throws Exception;
    }

    @FunctionalInterface
    private interface SqlConsumer<T> {
        void accept(T value) throws Exception;
    }
}
