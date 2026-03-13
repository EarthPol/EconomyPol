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

final class ReservationsDatabaseCheckRunner extends AbstractDatabaseCheckRunner {

    ReservationsDatabaseCheckRunner(DatabaseService databaseService, TownyService townyService) {
        super(databaseService, townyService);
    }

    @Override
    public String reportName() {
        return "reservations";
    }

    @Override
    public DatabaseCheckReport run(Instant ranAt, long startedNanos) {
        Map<String, Long> statistics = new LinkedHashMap<>();
        statistics.put("reservation_rows", queryLong("SELECT COUNT(*) FROM economy_reservations"));
        statistics.put("reservation_amount_total", queryLong("SELECT COALESCE(SUM(amount), 0) FROM economy_reservations"));
        statistics.put("reservation_active", queryLong("SELECT COUNT(*) FROM economy_reservations WHERE status = 'ACTIVE'"));
        statistics.put("reservation_captured", queryLong("SELECT COUNT(*) FROM economy_reservations WHERE status = 'CAPTURED'"));
        statistics.put("reservation_released", queryLong("SELECT COUNT(*) FROM economy_reservations WHERE status = 'RELEASED'"));

        List<DatabaseCheckFinding> findings = new ArrayList<>();
        findings.addAll(queryFindings(
                """
                SELECT reservation_id, status
                FROM economy_reservations
                WHERE status NOT IN ('ACTIVE', 'CAPTURED', 'RELEASED')
                """,
                resultSet -> new DatabaseCheckFinding(
                        "economy_reservations",
                        "reservation_id=" + resultSet.getString("reservation_id"),
                        "Unsupported reservation status=" + resultSet.getString("status")
                )
        ));
        findings.addAll(queryFindings(
                """
                SELECT reservation_id, amount
                FROM economy_reservations
                WHERE amount < 0
                """,
                resultSet -> new DatabaseCheckFinding(
                        "economy_reservations",
                        "reservation_id=" + resultSet.getString("reservation_id"),
                        "Negative reservation amount=" + resultSet.getLong("amount")
                )
        ));
        findings.addAll(queryFindings(
                """
                SELECT r.reservation_id
                FROM economy_reservations r
                LEFT JOIN economy_accounts a ON a.account_id = r.account_id
                WHERE a.account_id IS NULL
                """,
                resultSet -> new DatabaseCheckFinding(
                        "economy_reservations",
                        "reservation_id=" + resultSet.getString("reservation_id"),
                        "Reservation points to a missing account"
                )
        ));
        findings.addAll(queryFindings(
                """
                SELECT r.reservation_id, r.account_id, r.amount, COALESCE(b.reserved_balance, 0) AS reserved_balance
                FROM economy_reservations r
                LEFT JOIN economy_balances b ON b.account_id = r.account_id
                WHERE r.status = 'ACTIVE'
                  AND COALESCE(b.reserved_balance, 0) < r.amount
                """,
                resultSet -> new DatabaseCheckFinding(
                        "economy_reservations",
                        "reservation_id=" + resultSet.getString("reservation_id"),
                        "Active reservation amount=" + resultSet.getLong("amount") +
                                " exceeds reserved_balance=" + resultSet.getLong("reserved_balance") +
                                " for account_id=" + resultSet.getString("account_id")
                )
        ));

        return finish(
                ranAt,
                startedNanos,
                findings.isEmpty(),
                findings.isEmpty() ? "No malformed reservation rows found." : "Found " + findings.size() + " malformed reservation rows.",
                statistics,
                List.of(),
                findings
        );
    }
}
