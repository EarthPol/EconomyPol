package com.earthpol.economyPol.economy.service.databasecheck;

import com.earthpol.earthPolLib.database.DatabaseService;
import com.earthpol.economyPol.economy.model.DatabaseCheckFinding;
import com.earthpol.economyPol.economy.model.DatabaseCheckReport;
import com.earthpol.economyPol.towny.TownyService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
        statistics.put("duplicate_username_groups", queryLong("""
                SELECT COUNT(*)
                FROM (
                    SELECT username
                    FROM economy_players
                    WHERE TRIM(username) <> ''
                    GROUP BY username
                    HAVING COUNT(*) > 1
                ) duplicate_usernames
                """));
        statistics.put("duplicate_username_rows", queryLong("""
                SELECT COUNT(*)
                FROM economy_players
                WHERE username IN (
                    SELECT username
                    FROM (
                        SELECT username
                        FROM economy_players
                        WHERE TRIM(username) <> ''
                        GROUP BY username
                        HAVING COUNT(*) > 1
                    ) duplicate_usernames
                )
                """));

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
        findings.addAll(findDuplicateUsernameFindings());

        return finish(
                ranAt,
                startedNanos,
                findings.isEmpty(),
                findings.isEmpty() ? "No malformed account rows found." : "Found " + findings.size() + " account-related findings.",
                statistics,
                List.of(),
                findings
        );
    }

    private List<DatabaseCheckFinding> findDuplicateUsernameFindings() {
        List<DatabaseCheckFinding> findings = new ArrayList<>();
        String sql = """
                SELECT username, player_uuid, updated_at
                FROM economy_players
                WHERE username IN (
                    SELECT username
                    FROM (
                        SELECT username
                        FROM economy_players
                        WHERE TRIM(username) <> ''
                        GROUP BY username
                        HAVING COUNT(*) > 1
                    ) duplicate_usernames
                )
                ORDER BY username ASC, updated_at DESC, player_uuid ASC
                """;

        try (Connection connection = databaseService.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            String currentUsername = null;
            List<String> playerRows = new ArrayList<>();

            while (resultSet.next()) {
                String username = resultSet.getString("username");
                if (currentUsername != null && !currentUsername.equals(username)) {
                    findings.add(duplicateUsernameFinding(currentUsername, playerRows));
                    playerRows = new ArrayList<>();
                }
                currentUsername = username;
                playerRows.add("player_uuid=" + resultSet.getString("player_uuid")
                        + " updated_at=" + nullableTimestamp(resultSet, "updated_at"));
            }

            if (currentUsername != null) {
                findings.add(duplicateUsernameFinding(currentUsername, playerRows));
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to execute duplicate username accounts check.", exception);
        }

        return findings;
    }

    private DatabaseCheckFinding duplicateUsernameFinding(String username, List<String> playerRows) {
        return new DatabaseCheckFinding(
                "economy_players",
                "username=" + username,
                "Duplicate stored username matches multiple UUIDs: " + String.join("; ", playerRows)
        );
    }
}
