package com.earthpol.economyPol.economy.service.databasecheck;

import com.earthpol.earthpollib.database.DatabaseService;
import com.earthpol.economyPol.economy.model.DatabaseCheckReport;
import com.earthpol.economyPol.towny.TownyService;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class StatsDatabaseCheckRunner extends AbstractDatabaseCheckRunner {

    StatsDatabaseCheckRunner(DatabaseService databaseService, TownyService townyService) {
        super(databaseService, townyService);
    }

    @Override
    public String reportName() {
        return "stats";
    }

    @Override
    public DatabaseCheckReport run(Instant ranAt, long startedNanos) {
        Map<String, Long> statistics = new LinkedHashMap<>();
        long totalAccounts = queryLong("SELECT COUNT(*) FROM economy_accounts");
        long playerAccounts = queryLong("SELECT COUNT(*) FROM economy_accounts WHERE account_type = 'PLAYER'");
        long sharedAccounts = queryLong("SELECT COUNT(*) FROM economy_accounts WHERE account_type = 'SHARED'");
        long registeredPlayers = queryLong("SELECT COUNT(*) FROM economy_players");
        long availableTotal = queryLong("SELECT COALESCE(SUM(available_balance), 0) FROM economy_balances");
        long reservedTotal = queryLong("SELECT COALESCE(SUM(reserved_balance), 0) FROM economy_balances");
        long snapshotTotal = queryLong("SELECT COALESCE(SUM(base_units), 0) FROM economy_ender_wallet_snapshots");
        long activeReservations = queryLong("SELECT COUNT(*) FROM economy_reservations WHERE status = 'ACTIVE'");
        long ledgerEntries = queryLong("SELECT COUNT(*) FROM economy_ledger_entries");
        long pendingNotifications = queryLong("SELECT COUNT(*) FROM economy_player_notifications");
        long townyBindings = queryLong("SELECT COUNT(*) FROM economy_towny_governments");

        statistics.put("total_accounts", totalAccounts);
        statistics.put("player_accounts", playerAccounts);
        statistics.put("shared_accounts", sharedAccounts);
        statistics.put("registered_players", registeredPlayers);
        statistics.put("towny_government_bindings", townyBindings);
        statistics.put("custodial_available_total", availableTotal);
        statistics.put("custodial_reserved_total", reservedTotal);
        statistics.put("snapshot_base_units_total", snapshotTotal);
        statistics.put("database_tracked_total", availableTotal + reservedTotal + snapshotTotal);
        statistics.put("active_reservations", activeReservations);
        statistics.put("pending_player_notifications", pendingNotifications);
        statistics.put("ledger_entries", ledgerEntries);

        return finish(
                ranAt,
                startedNanos,
                true,
                "Collected database statistics. Database-tracked totals exclude live inventory money held outside custodial balances and snapshots.",
                statistics,
                List.of("database_tracked_total = custodial_available_total + custodial_reserved_total + snapshot_base_units_total"),
                List.of()
        );
    }
}
