package com.earthpol.economyPol.economy.repository;

import com.earthpol.earthPolLib.database.DatabaseService;
import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.model.EnderWalletSnapshot;
import com.earthpol.economyPol.economy.model.OfflineEnderWalletState;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class EnderWalletRepository extends AbstractRepositorySupport {

    public EnderWalletRepository(DatabaseService databaseService, EnhancedLogger operationsLog, EnhancedLogger auditLog) {
        super(databaseService, operationsLog, auditLog);
    }

    public Optional<EnderWalletSnapshot> findEnderWalletSnapshot(UUID playerUuid) {
        return queryOne("""
                SELECT player_uuid, base_units, state, last_clean_sync_at
                FROM economy_ender_wallet_snapshots
                WHERE player_uuid = ?
                """,
                statement -> statement.setObject(1, uuid(playerUuid)),
                resultSet -> new EnderWalletSnapshot(
                        parseUuid(resultSet.getObject("player_uuid")),
                        resultSet.getLong("base_units"),
                        OfflineEnderWalletState.valueOf(resultSet.getString("state")),
                        timestampMillis(resultSet, "last_clean_sync_at")
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
                timestampFromMillis(snapshot.lastCleanSyncAt()),
                nowTimestamp()
        );
    }

    public void deleteEnderWalletSnapshot(UUID playerUuid) {
        update("DELETE FROM economy_ender_wallet_snapshots WHERE player_uuid = ?", uuid(playerUuid));
    }

    public List<EnderWalletSnapshot> listFrozenEnderWalletSnapshots() {
        return queryList("""
                        SELECT player_uuid, base_units, state, last_clean_sync_at
                        FROM economy_ender_wallet_snapshots
                        WHERE state = ?
                        """,
                statement -> statement.setString(1, OfflineEnderWalletState.FROZEN.name()),
                resultSet -> new EnderWalletSnapshot(
                        parseUuid(resultSet.getObject("player_uuid")),
                        resultSet.getLong("base_units"),
                        OfflineEnderWalletState.valueOf(resultSet.getString("state")),
                        timestampMillis(resultSet, "last_clean_sync_at")
                )
        );
    }

    public List<EnderWalletSnapshot> markStaleSnapshotsDisabled() {
        return inTransaction(connection -> {
            List<EnderWalletSnapshot> staleSnapshots = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement("""
                    SELECT player_uuid, base_units, state, last_clean_sync_at
                    FROM economy_ender_wallet_snapshots
                    WHERE state = ?
                    FOR UPDATE
                    """)) {
                select.setString(1, OfflineEnderWalletState.SYNCING.name());
                try (ResultSet resultSet = select.executeQuery()) {
                    while (resultSet.next()) {
                        staleSnapshots.add(new EnderWalletSnapshot(
                                parseUuid(resultSet.getObject("player_uuid")),
                                resultSet.getLong("base_units"),
                                OfflineEnderWalletState.valueOf(resultSet.getString("state")),
                                timestampMillis(resultSet, "last_clean_sync_at")
                        ));
                    }
                }
            }

            if (staleSnapshots.isEmpty()) {
                return List.of();
            }

            try (PreparedStatement update = connection.prepareStatement("""
                    UPDATE economy_ender_wallet_snapshots
                    SET state = ?, updated_at = ?
                WHERE state = ?
                """)) {
                update.setString(1, OfflineEnderWalletState.DISABLED_UNCLEAN.name());
                update.setTimestamp(2, nowTimestamp());
                update.setString(3, OfflineEnderWalletState.SYNCING.name());
                update.executeUpdate();
            }
            return List.copyOf(staleSnapshots);
        });
    }
}
