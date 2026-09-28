package com.earthpol.economyPol.economy.service.databasecheck;

import com.earthpol.earthpollib.database.DatabaseService;
import com.earthpol.economyPol.economy.model.DatabaseCheckFinding;
import com.earthpol.economyPol.economy.model.DatabaseCheckReport;
import com.earthpol.economyPol.towny.TownyService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class BalancesDatabaseCheckRunner extends AbstractDatabaseCheckRunner {

    BalancesDatabaseCheckRunner(DatabaseService databaseService, TownyService townyService) {
        super(databaseService, townyService);
    }

    @Override
    public String reportName() {
        return "balances";
    }

    @Override
    public DatabaseCheckReport run(Instant ranAt, long startedNanos) {
        Map<String, Long> statistics = new LinkedHashMap<>();
        statistics.put("balance_rows", queryLong("SELECT COUNT(*) FROM economy_balances"));
        statistics.put("available_total", queryLong("SELECT COALESCE(SUM(available_balance), 0) FROM economy_balances"));
        statistics.put("reserved_total", queryLong("SELECT COALESCE(SUM(reserved_balance), 0) FROM economy_balances"));

        List<DatabaseCheckFinding> findings = new ArrayList<>();
        findings.addAll(queryFindings(
                """
                SELECT account_id, available_balance, reserved_balance
                FROM economy_balances
                WHERE available_balance < 0 OR reserved_balance < 0
                """,
                resultSet -> new DatabaseCheckFinding(
                        "economy_balances",
                        "account_id=" + resultSet.getString("account_id"),
                        "Negative balance values available=" + resultSet.getLong("available_balance") +
                                ", reserved=" + resultSet.getLong("reserved_balance")
                )
        ));
        findings.addAll(queryFindings(
                """
                SELECT b.account_id
                FROM economy_balances b
                LEFT JOIN economy_accounts a ON a.account_id = b.account_id
                WHERE a.account_id IS NULL
                """,
                resultSet -> new DatabaseCheckFinding(
                        "economy_balances",
                        "account_id=" + resultSet.getString("account_id"),
                        "Orphan balance row with no owning account"
                )
        ));

        return finish(
                ranAt,
                startedNanos,
                findings.isEmpty(),
                findings.isEmpty() ? "No malformed balance rows found." : "Found " + findings.size() + " malformed balance rows.",
                statistics,
                List.of(),
                findings
        );
    }
}
