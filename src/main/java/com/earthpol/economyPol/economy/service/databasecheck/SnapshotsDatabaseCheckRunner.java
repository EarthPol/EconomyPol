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

final class SnapshotsDatabaseCheckRunner extends AbstractDatabaseCheckRunner {

    SnapshotsDatabaseCheckRunner(DatabaseService databaseService, TownyService townyService) {
        super(databaseService, townyService);
    }

    @Override
    public String reportName() {
        return "snapshots";
    }

    @Override
    public DatabaseCheckReport run(Instant ranAt, long startedNanos) {
        Map<String, Long> statistics = new LinkedHashMap<>();
        statistics.put("snapshot_rows", queryLong("SELECT COUNT(*) FROM economy_ender_wallet_snapshots"));
        statistics.put("snapshot_base_units_total", queryLong("SELECT COALESCE(SUM(base_units), 0) FROM economy_ender_wallet_snapshots"));
        statistics.put("snapshot_frozen", queryLong("SELECT COUNT(*) FROM economy_ender_wallet_snapshots WHERE state = 'FROZEN'"));
        statistics.put("snapshot_syncing", queryLong("SELECT COUNT(*) FROM economy_ender_wallet_snapshots WHERE state = 'SYNCING'"));
        statistics.put("snapshot_disabled_unclean", queryLong("SELECT COUNT(*) FROM economy_ender_wallet_snapshots WHERE state = 'DISABLED_UNCLEAN'"));

        List<DatabaseCheckFinding> findings = new ArrayList<>();
        findings.addAll(queryFindings(
                """
                SELECT player_uuid, state
                FROM economy_ender_wallet_snapshots
                WHERE state NOT IN ('FROZEN', 'SYNCING', 'DISABLED_UNCLEAN')
                """,
                resultSet -> new DatabaseCheckFinding(
                        "economy_ender_wallet_snapshots",
                        "player_uuid=" + resultSet.getString("player_uuid"),
                        "Unsupported snapshot state=" + resultSet.getString("state")
                )
        ));
        findings.addAll(queryFindings(
                """
                SELECT player_uuid, base_units
                FROM economy_ender_wallet_snapshots
                WHERE base_units < 0
                """,
                resultSet -> new DatabaseCheckFinding(
                        "economy_ender_wallet_snapshots",
                        "player_uuid=" + resultSet.getString("player_uuid"),
                        "Negative base_units=" + resultSet.getLong("base_units")
                )
        ));

        List<String> notes = new ArrayList<>();
        if (statistics.get("snapshot_disabled_unclean") > 0L) {
            notes.add("Some snapshots are quarantined as DISABLED_UNCLEAN after startup recovery. Run 'unclean-snapshots' for row-level details.");
        }

        return finish(
                ranAt,
                startedNanos,
                findings.isEmpty(),
                findings.isEmpty() ? "No malformed ender-wallet snapshot rows found." : "Found " + findings.size() + " malformed snapshot rows.",
                statistics,
                notes,
                findings
        );
    }
}
