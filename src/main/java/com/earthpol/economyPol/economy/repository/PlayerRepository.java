package com.earthpol.economyPol.economy.repository;

import com.earthpol.earthpollib.database.DatabaseService;
import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.model.IncomingPaymentDeliveryPreference;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
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
                    INSERT INTO economy_players (
                        player_uuid,
                        username,
                        incoming_payment_delivery_preference,
                        skip_shulker_delivery,
                        created_at,
                        updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        username = VALUES(username),
                        updated_at = VALUES(updated_at)
                    """,
                    uuid(playerUuid),
                    namePlan.storedName(),
                    IncomingPaymentDeliveryPreference.DEFAULT.name(),
                    false,
                    now,
                    now
            );
            return;
        }
        update("""
                INSERT INTO economy_players (
                    player_uuid,
                    username,
                    incoming_payment_delivery_preference,
                    skip_shulker_delivery,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    updated_at = VALUES(updated_at)
                """,
                uuid(playerUuid),
                namePlan.storedName(),
                IncomingPaymentDeliveryPreference.DEFAULT.name(),
                false,
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

    public boolean getSkipShulkerDelivery(UUID playerUuid) {
        Optional<Boolean> skipShulkerDelivery = queryOne("""
                        SELECT skip_shulker_delivery
                        FROM economy_players
                        WHERE player_uuid = ?
                        """,
                statement -> bind(statement, uuid(playerUuid)),
                resultSet -> resultSet.getBoolean("skip_shulker_delivery")
        );
        return skipShulkerDelivery.orElse(false);
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

    public Optional<UUID> findPlayerUuidByUsername(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        List<UUID> playerUuids = findPlayerUuidsByUsername(username);
        return playerUuids.size() == 1 ? Optional.of(playerUuids.getFirst()) : Optional.empty();
    }

    public List<UUID> findPlayerUuidsByUsername(String username) {
        if (username == null || username.isBlank()) {
            return List.of();
        }
        return queryList("""
                        SELECT player_uuid
                        FROM economy_players
                        WHERE username = ?
                        ORDER BY player_uuid ASC
                        """,
                statement -> bind(statement, username),
                resultSet -> parseUuid(resultSet.getObject("player_uuid"))
        );
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

    public void setSkipShulkerDelivery(UUID playerUuid, boolean skipShulkerDelivery) {
        update("""
                UPDATE economy_players
                SET skip_shulker_delivery = ?,
                    updated_at = ?
                WHERE player_uuid = ?
                """,
                skipShulkerDelivery,
                nowTimestamp(),
                uuid(playerUuid)
        );
    }
}
