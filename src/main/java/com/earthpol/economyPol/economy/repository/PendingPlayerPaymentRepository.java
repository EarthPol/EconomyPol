package com.earthpol.economyPol.economy.repository;

import com.earthpol.earthPolLib.database.DatabaseService;
import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.logging.EconomyLoggers.LogType;
import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.model.OfflineEnderWalletState;
import com.earthpol.economyPol.economy.model.PendingPlayerPayment;
import com.earthpol.economyPol.economy.model.PendingPlayerPaymentAttemptResult;
import com.earthpol.economyPol.economy.model.PendingPlayerPaymentStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class PendingPlayerPaymentRepository extends AbstractRepositorySupport {

    public PendingPlayerPaymentRepository(DatabaseService databaseService, EconomyLoggers loggers) {
        super(databaseService, loggers);
    }

    public PendingPlayerPayment enqueuePayment(UUID playerUuid, long amount) {
        long createdAt = System.currentTimeMillis();
        PendingPlayerPayment payment = new PendingPlayerPayment(
                UUID.randomUUID(),
                playerUuid,
                amount,
                PendingPlayerPaymentStatus.PENDING,
                createdAt,
                null,
                PendingPlayerPaymentAttemptResult.NONE
        );
        inTransaction(connection -> {
            incrementPendingBalance(connection, playerUuid, amount);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO economy_pending_player_payments (
                        pending_payment_id,
                        player_uuid,
                        payment_amount,
                        status,
                        created_at,
                        last_attempted_delivery_at,
                        last_attempted_delivery_result
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setObject(1, uuid(payment.pendingPaymentId()));
                statement.setObject(2, uuid(payment.playerUuid()));
                statement.setLong(3, payment.paymentAmount());
                statement.setString(4, payment.status().name());
                statement.setTimestamp(5, new Timestamp(payment.createdAt()));
                statement.setTimestamp(6, null);
                statement.setString(7, payment.lastAttemptedDeliveryResult().name());
                statement.executeUpdate();
            }
            return payment;
        });
        loggers.log("pending-payment-enqueue id=" + payment.pendingPaymentId() +
                " player=" + playerUuid + " amount=" + amount, LogType.AUDIT);
        return payment;
    }

    public Optional<PendingPlayerPayment> claimNextRetryablePayment(UUID playerUuid, long staleBeforeMillis) {
        return inTransaction(connection -> {
            PendingPlayerPayment payment = selectNextRetryablePaymentForUpdate(connection, playerUuid, staleBeforeMillis);
            if (payment == null) {
                return Optional.empty();
            }
            long attemptedAt = System.currentTimeMillis();
            updateClaimState(
                    connection,
                    payment.pendingPaymentId(),
                    PendingPlayerPaymentStatus.PROCESSING,
                    attemptedAt,
                    PendingPlayerPaymentAttemptResult.NONE
            );
            return Optional.of(new PendingPlayerPayment(
                    payment.pendingPaymentId(),
                    payment.playerUuid(),
                    payment.paymentAmount(),
                    PendingPlayerPaymentStatus.PROCESSING,
                    payment.createdAt(),
                    attemptedAt,
                    PendingPlayerPaymentAttemptResult.NONE
            ));
        });
    }

    public void requeuePayment(UUID pendingPaymentId, PendingPlayerPaymentAttemptResult attemptResult) {
        update("""
                UPDATE economy_pending_player_payments
                SET status = ?, last_attempted_delivery_result = ?
                WHERE pending_payment_id = ?
                """,
                PendingPlayerPaymentStatus.PENDING.name(),
                attemptResult.name(),
                uuid(pendingPaymentId)
        );
    }

    public CompletionResult completePayment(UUID pendingPaymentId) {
        return completePayment(pendingPaymentId, null, 0L, null, null);
    }

    public boolean completePaymentToOfflineWallet(UUID pendingPaymentId) {
        return inTransaction(connection -> {
            PendingPlayerPayment payment = selectPaymentForUpdate(connection, pendingPaymentId);
            if (payment == null) {
                throw new IllegalStateException("Pending payment does not exist: " + pendingPaymentId);
            }
            OfflineWalletSnapshotRecord snapshot = selectOfflineWalletSnapshotForUpdate(connection, payment.playerUuid());
            if (snapshot == null || snapshot.state() != OfflineEnderWalletState.FROZEN) {
                return false;
            }
            updateOfflineWalletSnapshot(connection, payment.playerUuid(), snapshot.baseUnits() + payment.paymentAmount(), snapshot.state());
            decrementPendingBalance(connection, payment.playerUuid(), payment.paymentAmount());
            deletePayment(connection, pendingPaymentId);
            loggers.log("pending-payment-complete-offline-wallet id=" + pendingPaymentId +
                    " player=" + payment.playerUuid() + " amount=" + payment.paymentAmount(), LogType.AUDIT);
            return true;
        });
    }

    public CompletionResult completePaymentToCustodial(
            UUID pendingPaymentId,
            UUID accountId,
            long custodialAmount,
            String entryType,
            String reason
    ) {
        return completePayment(pendingPaymentId, accountId, custodialAmount, entryType, reason);
    }

    public long getPendingBalance(UUID playerUuid) {
        return queryOne("""
                SELECT pending_balance
                FROM economy_pending_player_payment_balances
                WHERE player_uuid = ?
                """,
                statement -> statement.setObject(1, uuid(playerUuid)),
                resultSet -> resultSet.getLong("pending_balance")
        ).orElse(0L);
    }

    public List<UUID> listPlayersWithRetryablePayments(int limit, long staleBeforeMillis) {
        return queryList("""
                SELECT player_uuid
                FROM economy_pending_player_payments
                WHERE status = ?
                   OR (status = ? AND last_attempted_delivery_at IS NOT NULL AND last_attempted_delivery_at < ?)
                GROUP BY player_uuid
                ORDER BY MIN(COALESCE(last_attempted_delivery_at, created_at)), player_uuid
                LIMIT ?
                """,
                statement -> {
                    statement.setString(1, PendingPlayerPaymentStatus.PENDING.name());
                    statement.setString(2, PendingPlayerPaymentStatus.PROCESSING.name());
                    statement.setTimestamp(3, new Timestamp(staleBeforeMillis));
                    statement.setInt(4, limit);
                },
                resultSet -> parseUuid(resultSet.getObject("player_uuid"))
        );
    }

    private CompletionResult completePayment(
            UUID pendingPaymentId,
            UUID accountId,
            long custodialAmount,
            String entryType,
            String reason
    ) {
        return inTransaction(connection -> {
            PendingPlayerPayment payment = selectPaymentForUpdate(connection, pendingPaymentId);
            if (payment == null) {
                throw new IllegalStateException("Pending payment does not exist: " + pendingPaymentId);
            }
            long amountToCustodial = custodialAmount;
            if (amountToCustodial < 0L || amountToCustodial > payment.paymentAmount()) {
                throw new IllegalArgumentException("Invalid custodial completion amount for pending payment " + pendingPaymentId);
            }

            decrementPendingBalance(connection, payment.playerUuid(), payment.paymentAmount());
            deletePayment(connection, pendingPaymentId);

            BalanceRecord updatedCustodialBalance = null;
            if (amountToCustodial > 0L) {
                if (accountId == null) {
                    throw new IllegalArgumentException("Account ID is required when crediting custodial.");
                }
                BalanceRecord current = selectBalanceForUpdate(connection, accountId);
                long nextAvailable = current.availableBalance() + amountToCustodial;
                updateBalance(connection, accountId, nextAvailable, current.reservedBalance());
                insertLedger(
                        connection,
                        accountId,
                        payment.playerUuid(),
                        amountToCustodial,
                        nextAvailable,
                        current.reservedBalance(),
                        entryType,
                        reason
                );
                updatedCustodialBalance = new BalanceRecord(nextAvailable, current.reservedBalance());
            }

            loggers.log("pending-payment-complete id=" + pendingPaymentId +
                    " player=" + payment.playerUuid() +
                    " amount=" + payment.paymentAmount() +
                    " custodial=" + amountToCustodial, LogType.AUDIT);
            return new CompletionResult(payment.paymentAmount(), updatedCustodialBalance);
        });
    }

    private PendingPlayerPayment selectNextRetryablePaymentForUpdate(
            Connection connection,
            UUID playerUuid,
            long staleBeforeMillis
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pending_payment_id, player_uuid, payment_amount, status, created_at,
                       last_attempted_delivery_at, last_attempted_delivery_result
                FROM economy_pending_player_payments
                WHERE player_uuid = ?
                  AND (
                    status = ?
                    OR (status = ? AND last_attempted_delivery_at IS NOT NULL AND last_attempted_delivery_at < ?)
                  )
                ORDER BY COALESCE(last_attempted_delivery_at, created_at), created_at, pending_payment_id
                LIMIT 1
                FOR UPDATE
                """)) {
            statement.setObject(1, uuid(playerUuid));
            statement.setString(2, PendingPlayerPaymentStatus.PENDING.name());
            statement.setString(3, PendingPlayerPaymentStatus.PROCESSING.name());
            statement.setTimestamp(4, new Timestamp(staleBeforeMillis));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return mapPayment(resultSet);
            }
        }
    }

    private PendingPlayerPayment selectPaymentForUpdate(Connection connection, UUID pendingPaymentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pending_payment_id, player_uuid, payment_amount, status, created_at,
                       last_attempted_delivery_at, last_attempted_delivery_result
                FROM economy_pending_player_payments
                WHERE pending_payment_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, uuid(pendingPaymentId));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return mapPayment(resultSet);
            }
        }
    }

    private void updateClaimState(
            Connection connection,
            UUID pendingPaymentId,
            PendingPlayerPaymentStatus status,
            long attemptedAt,
            PendingPlayerPaymentAttemptResult attemptResult
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE economy_pending_player_payments
                SET status = ?, last_attempted_delivery_at = ?, last_attempted_delivery_result = ?
                WHERE pending_payment_id = ?
                """)) {
            statement.setString(1, status.name());
            statement.setTimestamp(2, new Timestamp(attemptedAt));
            statement.setString(3, attemptResult.name());
            statement.setObject(4, uuid(pendingPaymentId));
            statement.executeUpdate();
        }
    }

    private void incrementPendingBalance(Connection connection, UUID playerUuid, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO economy_pending_player_payment_balances (player_uuid, pending_balance, updated_at)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    pending_balance = pending_balance + VALUES(pending_balance),
                    updated_at = VALUES(updated_at)
                """)) {
            statement.setObject(1, uuid(playerUuid));
            statement.setLong(2, amount);
            statement.setTimestamp(3, nowTimestamp());
            statement.executeUpdate();
        }
    }

    private void decrementPendingBalance(Connection connection, UUID playerUuid, long amount) throws SQLException {
        long currentBalance = selectPendingBalanceForUpdate(connection, playerUuid);
        if (currentBalance < amount) {
            throw new IllegalStateException("Insufficient pending payment balance for player " + playerUuid);
        }
        long nextBalance = currentBalance - amount;
        if (nextBalance == 0L) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    DELETE FROM economy_pending_player_payment_balances
                    WHERE player_uuid = ?
                    """)) {
                statement.setObject(1, uuid(playerUuid));
                statement.executeUpdate();
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE economy_pending_player_payment_balances
                SET pending_balance = ?, updated_at = ?
                WHERE player_uuid = ?
                """)) {
            statement.setLong(1, nextBalance);
            statement.setTimestamp(2, nowTimestamp());
            statement.setObject(3, uuid(playerUuid));
            statement.executeUpdate();
        }
    }

    private long selectPendingBalanceForUpdate(Connection connection, UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT pending_balance
                FROM economy_pending_player_payment_balances
                WHERE player_uuid = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, uuid(playerUuid));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return 0L;
                }
                return resultSet.getLong("pending_balance");
            }
        }
    }

    private void deletePayment(Connection connection, UUID pendingPaymentId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM economy_pending_player_payments
                WHERE pending_payment_id = ?
                """)) {
            statement.setObject(1, uuid(pendingPaymentId));
            statement.executeUpdate();
        }
    }

    private OfflineWalletSnapshotRecord selectOfflineWalletSnapshotForUpdate(Connection connection, UUID playerUuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT base_units, state
                FROM economy_ender_wallet_snapshots
                WHERE player_uuid = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, uuid(playerUuid));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new OfflineWalletSnapshotRecord(
                        resultSet.getLong("base_units"),
                        OfflineEnderWalletState.valueOf(resultSet.getString("state"))
                );
            }
        }
    }

    private void updateOfflineWalletSnapshot(
            Connection connection,
            UUID playerUuid,
            long baseUnits,
            OfflineEnderWalletState state
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE economy_ender_wallet_snapshots
                SET base_units = ?, state = ?, updated_at = ?
                WHERE player_uuid = ?
                """)) {
            statement.setLong(1, baseUnits);
            statement.setString(2, state.name());
            statement.setTimestamp(3, nowTimestamp());
            statement.setObject(4, uuid(playerUuid));
            statement.executeUpdate();
        }
    }

    private BalanceRecord selectBalanceForUpdate(Connection connection, UUID accountId) throws SQLException {
        ensureBalanceRow(connection, accountId);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT available_balance, reserved_balance
                FROM economy_balances
                WHERE account_id = ?
                FOR UPDATE
                """)) {
            statement.setObject(1, uuid(accountId));
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
            statement.setObject(1, uuid(accountId));
            statement.setTimestamp(2, nowTimestamp());
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
            statement.setTimestamp(3, nowTimestamp());
            statement.setObject(4, uuid(accountId));
            statement.executeUpdate();
        }
    }

    private void insertLedger(
            Connection connection,
            UUID accountId,
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
            statement.setObject(1, uuid(accountId));
            statement.setObject(2, null);
            statement.setObject(3, uuid(playerUuid));
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
            statement.setTimestamp(9, nowTimestamp());
            statement.executeUpdate();
        }
    }

    private PendingPlayerPayment mapPayment(ResultSet resultSet) throws SQLException {
        return new PendingPlayerPayment(
                parseUuid(resultSet.getObject("pending_payment_id")),
                parseUuid(resultSet.getObject("player_uuid")),
                resultSet.getLong("payment_amount"),
                PendingPlayerPaymentStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").getTime(),
                timestampMillis(resultSet, "last_attempted_delivery_at"),
                PendingPlayerPaymentAttemptResult.valueOf(resultSet.getString("last_attempted_delivery_result"))
        );
    }

    public record CompletionResult(long settledAmount, BalanceRecord updatedCustodialBalance) {}

    private record OfflineWalletSnapshotRecord(long baseUnits, OfflineEnderWalletState state) {}
}
