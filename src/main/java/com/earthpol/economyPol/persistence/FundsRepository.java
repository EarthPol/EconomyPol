package com.earthpol.economyPol.persistence;

import com.earthpol.earthPolLib.database.DatabaseService;
import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.domain.BalanceRecord;
import com.earthpol.economyPol.domain.ReservationRecord;
import com.earthpol.economyPol.domain.ReservationStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

public final class FundsRepository extends AbstractRepositorySupport {

    public FundsRepository(DatabaseService databaseService, EnhancedLogger operationsLog, EnhancedLogger auditLog) {
        super(databaseService, operationsLog, auditLog);
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

    public BalanceRecord settleReservedWithdrawal(
            UUID accountId,
            long deliveredAmount,
            long releasedAmount,
            String deliveredReason,
            String releasedReason,
            UUID playerUuid
    ) {
        return inTransaction(connection -> {
            if (deliveredAmount < 0L || releasedAmount < 0L) {
                throw new IllegalArgumentException("Delivered and released amounts must be non-negative.");
            }
            BalanceRecord current = selectBalanceForUpdate(connection, accountId);
            long totalSettled = deliveredAmount + releasedAmount;
            if (current.reservedBalance() < totalSettled) {
                throw new IllegalStateException("Insufficient reserved balance to settle physical withdrawal.");
            }

            long availableAfterRelease = current.availableBalance() + releasedAmount;
            long reservedAfterCapture = current.reservedBalance() - deliveredAmount;
            long finalReserved = reservedAfterCapture - releasedAmount;
            updateBalance(connection, accountId, availableAfterRelease, finalReserved);

            if (deliveredAmount > 0L) {
                insertLedger(
                        connection,
                        accountId,
                        null,
                        playerUuid,
                        -deliveredAmount,
                        current.availableBalance(),
                        reservedAfterCapture,
                        "RESERVATION_CAPTURE",
                        deliveredReason
                );
            }
            if (releasedAmount > 0L) {
                insertLedger(
                        connection,
                        accountId,
                        null,
                        playerUuid,
                        releasedAmount,
                        availableAfterRelease,
                        finalReserved,
                        "RESERVATION_RELEASE",
                        releasedReason
                );
            }
            return new BalanceRecord(availableAfterRelease, finalReserved);
        });
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
}
