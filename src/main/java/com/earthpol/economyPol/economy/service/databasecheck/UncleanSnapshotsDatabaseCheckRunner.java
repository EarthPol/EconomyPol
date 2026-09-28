package com.earthpol.economyPol.economy.service.databasecheck;

import com.earthpol.earthpollib.database.DatabaseService;
import com.earthpol.economyPol.economy.model.DatabaseCheckFinding;
import com.earthpol.economyPol.economy.model.DatabaseCheckReport;
import com.earthpol.economyPol.towny.TownyService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class UncleanSnapshotsDatabaseCheckRunner extends AbstractDatabaseCheckRunner {

    UncleanSnapshotsDatabaseCheckRunner(DatabaseService databaseService, TownyService townyService) {
        super(databaseService, townyService);
    }

    @Override
    public String reportName() {
        return "unclean-snapshots";
    }

    @Override
    public DatabaseCheckReport run(Instant ranAt, long startedNanos) {
        Map<String, Long> statistics = new LinkedHashMap<>();
        statistics.put("unclean_snapshot_rows", queryLong(
                "SELECT COUNT(*) FROM economy_ender_wallet_snapshots WHERE state = 'DISABLED_UNCLEAN'"
        ));
        statistics.put("unclean_snapshot_base_units_total", queryLong(
                "SELECT COALESCE(SUM(base_units), 0) FROM economy_ender_wallet_snapshots WHERE state = 'DISABLED_UNCLEAN'"
        ));

        List<DatabaseCheckFinding> findings = queryFindings(
                """
                SELECT s.player_uuid, s.base_units, s.last_clean_sync_at, s.updated_at, a.account_name
                FROM economy_ender_wallet_snapshots s
                LEFT JOIN economy_accounts a ON a.account_id = s.player_uuid
                WHERE s.state = 'DISABLED_UNCLEAN'
                ORDER BY s.updated_at ASC, s.player_uuid ASC
                """,
                resultSet -> new DatabaseCheckFinding(
                        "economy_ender_wallet_snapshots",
                        "player_uuid=" + resultSet.getString("player_uuid") +
                                (resultSet.getString("account_name") == null ? "" : ", account_name=" + resultSet.getString("account_name")),
                        "Snapshot quarantined after unclean startup recovery. base_units=" + resultSet.getLong("base_units") +
                                ", last_clean_sync_at=" + nullableTimestamp(resultSet, "last_clean_sync_at") +
                                ", updated_at=" + nullableTimestamp(resultSet, "updated_at")
                )
        );

        return finish(
                ranAt,
                startedNanos,
                findings.isEmpty(),
                findings.isEmpty()
                        ? "No DISABLED_UNCLEAN ender-wallet snapshots were found."
                        : "Found " + findings.size() + " DISABLED_UNCLEAN ender-wallet snapshots quarantined after startup recovery.",
                statistics,
                List.of(
                        "These rows indicate startup recovery encountered stale SYNCING snapshots after an unclean shutdown.",
                        "The rows are quarantined to avoid trusting ambiguous offline wallet state until a later clean player lifecycle rebuilds the snapshot."
                ),
                findings
        );
    }
}
