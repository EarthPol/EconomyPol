package com.earthpol.economyPol.economy.service.databasecheck;

import com.earthpol.earthPolLib.database.DatabaseService;
import com.earthpol.economyPol.economy.model.DatabaseCheckFinding;
import com.earthpol.economyPol.economy.model.DatabaseCheckReport;
import com.earthpol.economyPol.towny.TownyService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NotificationsDatabaseCheckRunner extends AbstractDatabaseCheckRunner {

    NotificationsDatabaseCheckRunner(DatabaseService databaseService, TownyService townyService) {
        super(databaseService, townyService);
    }

    @Override
    public String reportName() {
        return "notifications";
    }

    @Override
    public DatabaseCheckReport run(Instant ranAt, long startedNanos) {
        Map<String, Long> statistics = new LinkedHashMap<>();
        statistics.put("notification_rows", queryLong("SELECT COUNT(*) FROM economy_player_notifications"));
        statistics.put("offline_credit_notifications", queryLong(
                "SELECT COUNT(*) FROM economy_player_notifications WHERE notification_type = 'OFFLINE_CREDIT_TO_CUSTODIAL'"
        ));
        statistics.put("overflow_notifications", queryLong(
                "SELECT COUNT(*) FROM economy_player_notifications WHERE notification_type IN (" +
                        "'INCOMING_OVERFLOW_TO_CUSTODIAL', 'CUSTODIAL_WITHDRAWAL_RETAINED', " +
                        "'WALLET_OVERFLOW_TO_CUSTODIAL', 'CHANGE_ROUTED_TO_CUSTODIAL')"
        ));
        statistics.put("change_space_notifications", queryLong(
                "SELECT COUNT(*) FROM economy_player_notifications WHERE notification_type = 'NOT_ENOUGH_ROOM_FOR_CHANGE'"
        ));

        List<DatabaseCheckFinding> findings = new ArrayList<>();
        findings.addAll(queryFindings(
                """
                SELECT notification_id, notification_type
                FROM economy_player_notifications
                WHERE notification_type NOT IN (
                    'INCOMING_OVERFLOW_TO_CUSTODIAL',
                    'CUSTODIAL_WITHDRAWAL_RETAINED',
                    'WALLET_OVERFLOW_TO_CUSTODIAL',
                    'CUSTODIAL_BALANCE_REMINDER',
                    'OFFLINE_CREDIT_TO_CUSTODIAL',
                    'CHANGE_ROUTED_TO_CUSTODIAL',
                    'NOT_ENOUGH_ROOM_FOR_CHANGE'
                )
                """,
                resultSet -> new DatabaseCheckFinding(
                        "economy_player_notifications",
                        "notification_id=" + resultSet.getLong("notification_id"),
                        "Unsupported notification_type=" + resultSet.getString("notification_type")
                )
        ));
        findings.addAll(queryFindings(
                """
                SELECT n.notification_id
                FROM economy_player_notifications n
                LEFT JOIN economy_accounts a ON a.account_id = n.player_uuid AND a.account_type = 'PLAYER'
                WHERE a.account_id IS NULL
                """,
                resultSet -> new DatabaseCheckFinding(
                        "economy_player_notifications",
                        "notification_id=" + resultSet.getLong("notification_id"),
                        "Notification points to a missing player account"
                )
        ));

        return finish(
                ranAt,
                startedNanos,
                findings.isEmpty(),
                findings.isEmpty() ? "No malformed player notification rows found." : "Found " + findings.size() + " malformed notification rows.",
                statistics,
                List.of(),
                findings
        );
    }
}
