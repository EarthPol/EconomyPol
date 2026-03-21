package com.earthpol.economyPol.economy.repository;

import com.earthpol.earthPolLib.database.DatabaseService;
import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.model.IncomingPaymentDeliveryPreference;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class PlayerRepository extends AbstractRepositorySupport {

    public PlayerRepository(DatabaseService databaseService, EconomyLoggers loggers) {
        super(databaseService, loggers);
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

    public Optional<String> findUsername(UUID playerUuid) {
        return queryOne("""
                        SELECT username
                        FROM economy_players
                        WHERE player_uuid = ?
                        """,
                statement -> bind(statement, uuid(playerUuid)),
                resultSet -> resultSet.getString("username")
        ).filter(username -> username != null && !username.isBlank());
    }

    public Map<UUID, String> listUsernames() {
        Map<UUID, String> usernames = new LinkedHashMap<>();
        queryList(
                """
                SELECT player_uuid, username
                FROM economy_players
                ORDER BY username ASC
                """,
                statement -> {
                },
                resultSet -> {
                    String username = resultSet.getString("username");
                    if (username != null && !username.isBlank()) {
                        usernames.put(parseUuid(resultSet.getObject("player_uuid")), username);
                    }
                    return null;
                }
        );
        return Map.copyOf(usernames);
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
