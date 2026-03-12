package com.earthpol.economyPol.persistence;

import com.earthpol.earthPolLib.database.DatabaseService;
import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.domain.PlayerNotificationRecord;
import com.earthpol.economyPol.domain.PlayerNotificationType;

import java.util.List;
import java.util.UUID;

public final class NotificationRepository extends AbstractRepositorySupport {

    public NotificationRepository(DatabaseService databaseService, EnhancedLogger operationsLog, EnhancedLogger auditLog) {
        super(databaseService, operationsLog, auditLog);
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
}
