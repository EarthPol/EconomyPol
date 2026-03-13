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

final class AccountsDatabaseCheckRunner extends AbstractDatabaseCheckRunner {

    AccountsDatabaseCheckRunner(DatabaseService databaseService, TownyService townyService) {
        super(databaseService, townyService);
    }

    @Override
    public String reportName() {
        return "accounts";
    }

    @Override
    public DatabaseCheckReport run(Instant ranAt, long startedNanos) {
        Map<String, Long> statistics = new LinkedHashMap<>();
        statistics.put("total_accounts", queryLong("SELECT COUNT(*) FROM economy_accounts"));
        statistics.put("player_accounts", queryLong("SELECT COUNT(*) FROM economy_accounts WHERE account_type = 'PLAYER'"));
        statistics.put("shared_accounts", queryLong("SELECT COUNT(*) FROM economy_accounts WHERE account_type = 'SHARED'"));
        statistics.put("registered_players", queryLong("SELECT COUNT(*) FROM economy_players"));

        List<DatabaseCheckFinding> findings = new ArrayList<>();
        findings.addAll(queryFindings(
                "SELECT account_id, account_type FROM economy_accounts WHERE account_type NOT IN ('PLAYER', 'SHARED')",
                resultSet -> new DatabaseCheckFinding(
                        "economy_accounts",
                        "account_id=" + resultSet.getString("account_id"),
                        "Unsupported account_type=" + resultSet.getString("account_type")
                )
        ));
        findings.addAll(queryFindings(
                """
                SELECT account_id, owner_uuid
                FROM economy_accounts
                WHERE account_type = 'PLAYER'
                  AND (owner_uuid IS NULL OR owner_uuid <> account_id)
                """,
                resultSet -> new DatabaseCheckFinding(
                        "economy_accounts",
                        "account_id=" + resultSet.getString("account_id"),
                        "Player account must have owner_uuid equal to account_id; owner_uuid=" + resultSet.getString("owner_uuid")
                )
        ));
        findings.addAll(queryFindings(
                """
                SELECT a.account_id
                FROM economy_accounts a
                LEFT JOIN economy_players p ON p.player_uuid = a.account_id
                WHERE a.account_type = 'PLAYER'
                  AND p.player_uuid IS NULL
                """,
                resultSet -> new DatabaseCheckFinding(
                        "economy_accounts",
                        "account_id=" + resultSet.getString("account_id"),
                        "Player account is missing its economy_players row"
                )
        ));
        findings.addAll(queryFindings(
                """
                SELECT account_id
                FROM economy_accounts
                WHERE account_name IS NULL OR TRIM(account_name) = ''
                """,
                resultSet -> new DatabaseCheckFinding(
                        "economy_accounts",
                        "account_id=" + resultSet.getString("account_id"),
                        "Account name is blank"
                )
        ));
        findings.addAll(queryFindings(
                """
                SELECT a.account_id
                FROM economy_accounts a
                LEFT JOIN economy_balances b ON b.account_id = a.account_id
                WHERE b.account_id IS NULL
                """,
                resultSet -> new DatabaseCheckFinding(
                        "economy_accounts",
                        "account_id=" + resultSet.getString("account_id"),
                        "Account is missing a balance row"
                )
        ));

        return finish(
                ranAt,
                startedNanos,
                findings.isEmpty(),
                findings.isEmpty() ? "No malformed account rows found." : "Found " + findings.size() + " malformed account-related rows.",
                statistics,
                List.of(),
                findings
        );
    }
}
