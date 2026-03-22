package com.earthpol.economyPol.economy.repository;

import com.earthpol.earthPolLib.database.DatabaseService;
import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.AccountType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class AccountRepository extends AbstractRepositorySupport {

    public AccountRepository(DatabaseService databaseService, EconomyLoggers loggers) {
        super(databaseService, loggers);
    }

    public AccountRecord ensurePlayerAccount(UUID playerUuid, String ignoredPlayerName) {
        // Player accounts are keyed internally by a stable UUID-backed name.
        // Mutable display usernames live in economy_players.username instead.
        String storedName = playerUuid.toString();
        Timestamp now = nowTimestamp();
        update("""
                INSERT INTO economy_accounts (
                    account_id, account_type, owner_uuid, account_name, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    account_name = VALUES(account_name),
                    updated_at = VALUES(updated_at)
                """,
                uuid(playerUuid),
                AccountType.PLAYER,
                uuid(playerUuid),
                storedName,
                now,
                now
        );
        ensureBalanceRow(playerUuid);
        return findPlayerAccount(playerUuid)
                .orElse(new AccountRecord(playerUuid, AccountType.PLAYER, playerUuid, storedName));
    }

    public AccountRecord ensureSharedAccount(String name, UUID ownerUuid) {
        UUID accountId = UUID.nameUUIDFromBytes(("shared:" + name.toLowerCase()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return ensureSharedAccount(accountId, name, ownerUuid);
    }

    public AccountRecord ensureSharedAccount(UUID accountId, String name, UUID ownerUuid) {
        UUID resolvedOwnerUuid = ownerUuid == null ? accountId : ownerUuid;
        Timestamp now = nowTimestamp();
        update("""
                INSERT INTO economy_accounts (
                    account_id, account_type, owner_uuid, account_name, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    owner_uuid = VALUES(owner_uuid),
                    account_name = VALUES(account_name),
                    updated_at = VALUES(updated_at)
                """,
                uuid(accountId),
                AccountType.SHARED,
                uuid(resolvedOwnerUuid),
                name,
                now,
                now
        );
        ensureBalanceRow(accountId);
        return new AccountRecord(accountId, AccountType.SHARED, resolvedOwnerUuid, name);
    }

    public Optional<AccountRecord> findAccount(UUID accountId) {
        return queryOne("""
                SELECT account_id, account_type, owner_uuid, account_name
                FROM economy_accounts
                WHERE account_id = ?
                """,
                statement -> statement.setObject(1, uuid(accountId)),
                this::readAccount
        );
    }

    public Optional<AccountRecord> findPlayerAccount(UUID playerUuid) {
        return queryOne("""
                SELECT account_id, account_type, owner_uuid, account_name
                FROM economy_accounts
                WHERE account_id = ? AND account_type = ?
                """,
                statement -> {
                    statement.setObject(1, uuid(playerUuid));
                    bindEnum(statement, 2, AccountType.PLAYER);
                },
                this::readAccount
        );
    }

    public Optional<AccountRecord> findSharedAccount(String name) {
        return queryOne("""
                SELECT account_id, account_type, owner_uuid, account_name
                FROM economy_accounts
                WHERE account_name = ? AND account_type = ?
                """,
                statement -> {
                    statement.setString(1, name);
                    bindEnum(statement, 2, AccountType.SHARED);
                },
                this::readAccount
        );
    }

    public Optional<AccountRecord> findSharedAccount(UUID accountId) {
        return queryOne("""
                SELECT account_id, account_type, owner_uuid, account_name
                FROM economy_accounts
                WHERE account_id = ? AND account_type = ?
                """,
                statement -> {
                    statement.setObject(1, uuid(accountId));
                    bindEnum(statement, 2, AccountType.SHARED);
                },
                this::readAccount
        );
    }

    public Optional<AccountRecord> findAccountByName(String name) {
        return queryOne("""
                SELECT account_id, account_type, owner_uuid, account_name
                FROM economy_accounts
                WHERE account_name = ?
                """,
                statement -> statement.setString(1, name),
                this::readAccount
        );
    }

    public List<String> listSharedAccountNames() {
        return queryList(
                "SELECT account_name FROM economy_accounts WHERE account_type = ? ORDER BY account_name ASC",
                statement -> bindEnum(statement, 1, AccountType.SHARED),
                resultSet -> resultSet.getString(1)
        );
    }

    public List<AccountRecord> listSharedAccounts() {
        return queryList(
                """
                SELECT account_id, account_type, owner_uuid, account_name
                FROM economy_accounts
                WHERE account_type = ?
                ORDER BY account_name ASC
                """,
                statement -> bindEnum(statement, 1, AccountType.SHARED),
                this::readAccount
        );
    }

    public Map<UUID, String> listAccountNames() {
        Map<UUID, String> names = new LinkedHashMap<>();
        queryList(
                "SELECT account_id, account_name FROM economy_accounts ORDER BY account_name ASC",
                statement -> {
                },
                resultSet -> {
                    names.put(parseUuid(resultSet.getObject("account_id")), resultSet.getString("account_name"));
                    return null;
                }
        );
        return Map.copyOf(names);
    }

    public List<AccountRecord> listAccounts() {
        return queryList(
                """
                SELECT account_id, account_type, owner_uuid, account_name
                FROM economy_accounts
                ORDER BY account_name ASC
                """,
                statement -> {
                },
                this::readAccount
        );
    }

    public boolean renameAccount(UUID accountId, String newName) {
        return updateCount(
                "UPDATE economy_accounts SET account_name = ?, updated_at = ? WHERE account_id = ?",
                newName,
                nowTimestamp(),
                uuid(accountId)
        ) > 0;
    }

    public boolean updateSharedAccountOwner(UUID accountId, UUID ownerUuid) {
        return updateCount(
                "UPDATE economy_accounts SET owner_uuid = ?, updated_at = ? WHERE account_id = ? AND account_type = ?",
                uuid(ownerUuid),
                nowTimestamp(),
                uuid(accountId),
                AccountType.SHARED
        ) > 0;
    }

    public boolean deleteSharedAccount(UUID accountId) {
        return inTransaction(connection -> {
            deleteWhere(connection, "DELETE FROM economy_account_members WHERE account_id = ?", uuid(accountId));
            deleteWhere(connection, "DELETE FROM economy_reservations WHERE account_id = ?", uuid(accountId));
            deleteWhere(connection, "DELETE FROM economy_balances WHERE account_id = ?", uuid(accountId));
            deleteWhere(connection, "DELETE FROM economy_ledger_entries WHERE account_id = ? OR related_account_id = ?", uuid(accountId), uuid(accountId));
            int deletedAccounts = deleteWhere(
                    connection,
                    "DELETE FROM economy_accounts WHERE account_id = ? AND account_type = ?",
                    uuid(accountId),
                    AccountType.SHARED
            );
            return deletedAccounts > 0;
        });
    }

    public void upsertAccountMember(UUID accountId, UUID memberUuid) {
        update("""
                INSERT INTO economy_account_members (account_id, member_uuid, created_at)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE created_at = created_at
                """,
                uuid(accountId),
                uuid(memberUuid),
                nowTimestamp()
        );
    }

    public boolean removeAccountMember(UUID accountId, UUID memberUuid) {
        return updateCount(
                "DELETE FROM economy_account_members WHERE account_id = ? AND member_uuid = ?",
                uuid(accountId),
                uuid(memberUuid)
        ) > 0;
    }

    public boolean isAccountMember(UUID accountId, UUID memberUuid) {
        return queryOne("""
                SELECT 1
                FROM economy_account_members
                WHERE account_id = ? AND member_uuid = ?
                """,
                statement -> {
                    statement.setObject(1, uuid(accountId));
                    statement.setObject(2, uuid(memberUuid));
                },
                resultSet -> true
        ).orElse(false);
    }

    private AccountRecord readAccount(ResultSet resultSet) throws SQLException {
        return new AccountRecord(
                parseUuid(resultSet.getObject("account_id")),
                parseEnum(resultSet, "account_type", AccountType.class),
                parseUuid(resultSet.getObject("owner_uuid")),
                resultSet.getString("account_name")
        );
    }

    private void ensureBalanceRow(UUID accountId) {
        update("""
                INSERT INTO economy_balances (account_id, available_balance, reserved_balance, updated_at)
                VALUES (?, 0, 0, ?)
                ON DUPLICATE KEY UPDATE updated_at = updated_at
                """,
                uuid(accountId),
                nowTimestamp()
        );
    }
}
