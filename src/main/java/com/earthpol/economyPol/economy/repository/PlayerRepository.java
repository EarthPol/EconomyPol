package com.earthpol.economyPol.economy.repository;

import com.earthpol.earthPolLib.database.DatabaseService;
import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.model.IncomingPaymentDeliveryPreference;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

public final class PlayerRepository extends AbstractRepositorySupport {

    public PlayerRepository(DatabaseService databaseService, EnhancedLogger operationsLog, EnhancedLogger auditLog) {
        super(databaseService, operationsLog, auditLog);
    }

    public void ensurePlayer(UUID playerUuid, String username) {
        PlayerNameUpsertPlan namePlan = PlayerNameUpsertPlan.from(playerUuid, username);
        Timestamp now = nowTimestamp();
        if (namePlan.overwriteExisting()) {
            update("""
                    INSERT INTO economy_players (player_uuid, username, incoming_payment_delivery_preference, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        username = VALUES(username),
                        updated_at = VALUES(updated_at)
                    """,
                    uuid(playerUuid),
                    namePlan.storedName(),
                    IncomingPaymentDeliveryPreference.DEFAULT.name(),
                    now,
                    now
            );
            return;
        }
        update("""
                INSERT INTO economy_players (player_uuid, username, incoming_payment_delivery_preference, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    updated_at = VALUES(updated_at)
                """,
                uuid(playerUuid),
                namePlan.storedName(),
                IncomingPaymentDeliveryPreference.DEFAULT.name(),
                now,
                now
        );
    }

    public IncomingPaymentDeliveryPreference getIncomingPaymentDeliveryPreference(UUID playerUuid) {
        Optional<IncomingPaymentDeliveryPreference> preference = queryOne("""
                        SELECT incoming_payment_delivery_preference
                        FROM economy_players
                        WHERE player_uuid = ?
                        """,
                statement -> bind(statement, uuid(playerUuid)),
                resultSet -> IncomingPaymentDeliveryPreference.valueOf(
                        resultSet.getString("incoming_payment_delivery_preference")
                )
        );
        return preference.orElse(IncomingPaymentDeliveryPreference.DEFAULT);
    }

    public void setIncomingPaymentDeliveryPreference(UUID playerUuid, IncomingPaymentDeliveryPreference preference) {
        update("""
                UPDATE economy_players
                SET incoming_payment_delivery_preference = ?,
                    updated_at = ?
                WHERE player_uuid = ?
                """,
                preference.name(),
                nowTimestamp(),
                uuid(playerUuid)
        );
    }
}
