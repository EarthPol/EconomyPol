package com.earthpol.economyPol.economy.repository;

import com.earthpol.earthPolLib.database.DatabaseService;
import com.earthpol.earthPolLib.logging.EnhancedLogger;

import java.sql.Timestamp;
import java.util.UUID;

public final class PlayerRepository extends AbstractRepositorySupport {

    public PlayerRepository(DatabaseService databaseService, EnhancedLogger operationsLog, EnhancedLogger auditLog) {
        super(databaseService, operationsLog, auditLog);
    }

    public void ensurePlayer(UUID playerUuid, String username) {
        Timestamp now = nowTimestamp();
        update("""
                INSERT INTO economy_players (player_uuid, username, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    username = VALUES(username),
                    updated_at = VALUES(updated_at)
                """,
                uuid(playerUuid),
                normalizeUsername(playerUuid, username),
                now,
                now
        );
    }

    private String normalizeUsername(UUID playerUuid, String username) {
        if (username == null || username.isBlank()) {
            return playerUuid.toString();
        }
        return username;
    }
}
