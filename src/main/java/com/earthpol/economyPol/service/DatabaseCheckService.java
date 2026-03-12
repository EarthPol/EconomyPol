package com.earthpol.economyPol.service;

import com.earthpol.earthPolLib.database.DatabaseService;
import com.earthpol.economyPol.model.DatabaseCheckFinding;
import com.earthpol.economyPol.model.DatabaseCheckReport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DatabaseCheckService {

    private static final List<String> REPORT_NAMES = List.of(
            "accounts",
            "balances",
            "snapshots",
            "unclean-snapshots",
            "reservations",
            "notifications",
            "towny-accounts",
            "stats"
    );

    private final DatabaseService databaseService;
    private final TownyDiagnosticsService townyDiagnosticsService;

    public DatabaseCheckService(DatabaseService databaseService, TownyDiagnosticsService townyDiagnosticsService) {
        this.databaseService = databaseService;
        this.townyDiagnosticsService = townyDiagnosticsService;
    }

    public List<String> availableReports() {
        return REPORT_NAMES;
    }

    public DatabaseCheckReport runReport(String reportName) {
        String normalized = reportName == null ? "" : reportName.trim().toLowerCase();
        Instant ranAt = Instant.now();
        long startedNanos = System.nanoTime();
        try {
            return switch (normalized) {
                case "accounts" -> checkAccounts(ranAt, startedNanos);
                case "balances" -> checkBalances(ranAt, startedNanos);
                case "snapshots" -> checkSnapshots(ranAt, startedNanos);
                case "unclean-snapshots" -> checkUncleanSnapshots(ranAt, startedNanos);
                case "reservations" -> checkReservations(ranAt, startedNanos);
                case "notifications" -> checkNotifications(ranAt, startedNanos);
                case "towny-accounts" -> checkTownyAccounts(ranAt, startedNanos);
                case "stats" -> checkStats(ranAt, startedNanos);
                default -> finish(
                        normalized.isBlank() ? "unknown" : normalized,
                        ranAt,
                        startedNanos,
                        false,
                        "Unknown report alias. Available reports: " + String.join(", ", REPORT_NAMES) + ".",
                        Map.of(),
                        List.of(),
                        List.of(new DatabaseCheckFinding("SYSTEM", "report=" + reportName, "Unsupported database check alias"))
                );
            };
        } catch (Exception exception) {
            return finish(
                    normalized.isBlank() ? "unknown" : normalized,
                    ranAt,
                    startedNanos,
                    false,
                    "Database check failed with an exception.",
                    Map.of(),
                    List.of(exception.getClass().getSimpleName() + ": " + exception.getMessage()),
                    List.of(new DatabaseCheckFinding("SYSTEM", "report=" + normalized, "Check execution error"))
            );
        }
    }

    private DatabaseCheckReport checkAccounts(Instant ranAt, long startedNanos) {
        Map<String, Long> statistics = new LinkedHashMap<>();
        statistics.put("total_accounts", queryLong("SELECT COUNT(*) FROM economy_accounts"));
        statistics.put("player_accounts", queryLong("SELECT COUNT(*) FROM economy_accounts WHERE account_type = 'PLAYER'"));
        statistics.put("shared_accounts", queryLong("SELECT COUNT(*) FROM economy_accounts WHERE account_type = 'SHARED'"));

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
                "accounts",
                ranAt,
                startedNanos,
                findings.isEmpty(),
                findings.isEmpty() ? "No malformed account rows found." : "Found " + findings.size() + " malformed account-related rows.",
                statistics,
                List.of(),
                findings
        );
    }

    private DatabaseCheckReport checkBalances(Instant ranAt, long startedNanos) {
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
                "balances",
                ranAt,
                startedNanos,
                findings.isEmpty(),
                findings.isEmpty() ? "No malformed balance rows found." : "Found " + findings.size() + " malformed balance rows.",
                statistics,
                List.of(),
                findings
        );
    }

    private DatabaseCheckReport checkSnapshots(Instant ranAt, long startedNanos) {
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
                "snapshots",
                ranAt,
                startedNanos,
                findings.isEmpty(),
                findings.isEmpty() ? "No malformed ender-wallet snapshot rows found." : "Found " + findings.size() + " malformed snapshot rows.",
                statistics,
                notes,
                findings
        );
    }

    private DatabaseCheckReport checkUncleanSnapshots(Instant ranAt, long startedNanos) {
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
                                ", last_clean_sync_at=" + nullableLong(resultSet, "last_clean_sync_at") +
                                ", updated_at=" + resultSet.getLong("updated_at")
                )
        );

        return finish(
                "unclean-snapshots",
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

    private DatabaseCheckReport checkReservations(Instant ranAt, long startedNanos) {
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
                "reservations",
                ranAt,
                startedNanos,
                findings.isEmpty(),
                findings.isEmpty() ? "No malformed reservation rows found." : "Found " + findings.size() + " malformed reservation rows.",
                statistics,
                List.of(),
                findings
        );
    }

    private DatabaseCheckReport checkNotifications(Instant ranAt, long startedNanos) {
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
                "notifications",
                ranAt,
                startedNanos,
                findings.isEmpty(),
                findings.isEmpty() ? "No malformed player notification rows found." : "Found " + findings.size() + " malformed notification rows.",
                statistics,
                List.of(),
                findings
        );
    }

    private DatabaseCheckReport checkTownyAccounts(Instant ranAt, long startedNanos) {
        TownyAccountScanResult result = townyDiagnosticsService.scanAccounts();
        return finish(
                "towny-accounts",
                ranAt,
                startedNanos,
                result.available() && result.findings().isEmpty(),
                result.summary(),
                result.statistics(),
                result.notes(),
                result.findings()
        );
    }

    private DatabaseCheckReport checkStats(Instant ranAt, long startedNanos) {
        Map<String, Long> statistics = new LinkedHashMap<>();
        long totalAccounts = queryLong("SELECT COUNT(*) FROM economy_accounts");
        long playerAccounts = queryLong("SELECT COUNT(*) FROM economy_accounts WHERE account_type = 'PLAYER'");
        long sharedAccounts = queryLong("SELECT COUNT(*) FROM economy_accounts WHERE account_type = 'SHARED'");
        long availableTotal = queryLong("SELECT COALESCE(SUM(available_balance), 0) FROM economy_balances");
        long reservedTotal = queryLong("SELECT COALESCE(SUM(reserved_balance), 0) FROM economy_balances");
        long snapshotTotal = queryLong("SELECT COALESCE(SUM(base_units), 0) FROM economy_ender_wallet_snapshots");
        long activeReservations = queryLong("SELECT COUNT(*) FROM economy_reservations WHERE status = 'ACTIVE'");
        long ledgerEntries = queryLong("SELECT COUNT(*) FROM economy_ledger_entries");
        long pendingNotifications = queryLong("SELECT COUNT(*) FROM economy_player_notifications");

        statistics.put("total_accounts", totalAccounts);
        statistics.put("player_accounts", playerAccounts);
        statistics.put("shared_accounts", sharedAccounts);
        statistics.put("custodial_available_total", availableTotal);
        statistics.put("custodial_reserved_total", reservedTotal);
        statistics.put("snapshot_base_units_total", snapshotTotal);
        statistics.put("database_tracked_total", availableTotal + reservedTotal + snapshotTotal);
        statistics.put("active_reservations", activeReservations);
        statistics.put("pending_player_notifications", pendingNotifications);
        statistics.put("ledger_entries", ledgerEntries);

        return finish(
                "stats",
                ranAt,
                startedNanos,
                true,
                "Collected database statistics. Database-tracked totals exclude live inventory money held outside custodial balances and snapshots.",
                statistics,
                List.of("database_tracked_total = custodial_available_total + custodial_reserved_total + snapshot_base_units_total"),
                List.of()
        );
    }

    private long queryLong(String sql) {
        try (Connection connection = databaseService.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return 0L;
            }
            return resultSet.getLong(1);
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to execute query: " + sql, exception);
        }
    }

    private List<DatabaseCheckFinding> queryFindings(String sql, ResultSetMapper<DatabaseCheckFinding> mapper) {
        List<DatabaseCheckFinding> findings = new ArrayList<>();
        try (Connection connection = databaseService.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                findings.add(mapper.map(resultSet));
            }
        } catch (SQLException exception) {
            throw new RuntimeException("Failed to execute query: " + sql, exception);
        }
        return findings;
    }

    private DatabaseCheckReport finish(
            String reportName,
            Instant ranAt,
            long startedNanos,
            boolean healthy,
            String summary,
            Map<String, Long> statistics,
            List<String> notes,
            List<DatabaseCheckFinding> findings
    ) {
        long durationMillis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        return new DatabaseCheckReport(reportName, ranAt, durationMillis, healthy, summary, statistics, notes, findings);
    }

    private Long nullableLong(ResultSet resultSet, String columnName) throws SQLException {
        long value = resultSet.getLong(columnName);
        return resultSet.wasNull() ? null : value;
    }

    @FunctionalInterface
    private interface ResultSetMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }
}
