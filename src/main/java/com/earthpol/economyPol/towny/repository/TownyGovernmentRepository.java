package com.earthpol.economyPol.towny.repository;

import com.earthpol.earthpollib.database.DatabaseService;
import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.repository.AbstractRepositorySupport;
import com.earthpol.economyPol.towny.model.TownyGovernmentBinding;
import com.earthpol.economyPol.towny.model.TownyGovernmentType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class TownyGovernmentRepository extends AbstractRepositorySupport {

    public TownyGovernmentRepository(DatabaseService databaseService, EconomyLoggers loggers) {
        super(databaseService, loggers);
    }

    public TownyGovernmentBinding upsertBinding(
            UUID accountId,
            TownyGovernmentType governmentType,
            UUID governmentUuid,
            UUID bankAccountUuid,
            String governmentName,
            String bankAccountName
    ) {
        Timestamp now = nowTimestamp();
        update("""
                INSERT INTO economy_towny_governments (
                    government_uuid,
                    account_id,
                    government_type,
                    bank_account_uuid,
                    government_name,
                    bank_account_name,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    government_type = VALUES(government_type),
                    government_uuid = VALUES(government_uuid),
                    bank_account_uuid = VALUES(bank_account_uuid),
                    government_name = VALUES(government_name),
                    bank_account_name = VALUES(bank_account_name),
                    updated_at = VALUES(updated_at)
                """,
                uuid(governmentUuid),
                uuid(accountId),
                governmentType.name(),
                uuid(bankAccountUuid),
                governmentName,
                bankAccountName,
                now,
                now
        );
        return findByAccountId(accountId).orElseThrow(() -> new IllegalStateException(
                "Failed to load Towny government binding for account " + accountId
        ));
    }

    public Optional<TownyGovernmentBinding> findByAccountId(UUID accountId) {
        return queryOne(
                """
                SELECT government_uuid, government_type, account_id, bank_account_uuid,
                       government_name, bank_account_name, created_at, updated_at
                FROM economy_towny_governments
                WHERE account_id = ?
                """,
                statement -> statement.setObject(1, uuid(accountId)),
                this::readBinding
        );
    }

    public Optional<TownyGovernmentBinding> findByGovernment(TownyGovernmentType governmentType, UUID governmentUuid) {
        return queryOne(
                """
                SELECT government_uuid, government_type, account_id, bank_account_uuid,
                       government_name, bank_account_name, created_at, updated_at
                FROM economy_towny_governments
                WHERE government_type = ? AND government_uuid = ?
                """,
                statement -> {
                    statement.setString(1, governmentType.name());
                    statement.setObject(2, uuid(governmentUuid));
                },
                this::readBinding
        );
    }

    public List<TownyGovernmentBinding> listBindings() {
        return queryList(
                """
                SELECT government_uuid, government_type, account_id, bank_account_uuid,
                       government_name, bank_account_name, created_at, updated_at
                FROM economy_towny_governments
                ORDER BY government_type ASC, government_name ASC, account_id ASC
                """,
                statement -> {
                },
                this::readBinding
        );
    }

    public boolean deleteByAccountId(UUID accountId) {
        return updateCount(
                "DELETE FROM economy_towny_governments WHERE account_id = ?",
                uuid(accountId)
        ) > 0;
    }

    private TownyGovernmentBinding readBinding(ResultSet resultSet) throws SQLException {
        return new TownyGovernmentBinding(
                parseUuid(resultSet.getObject("government_uuid")),
                TownyGovernmentType.valueOf(resultSet.getString("government_type")),
                parseUuid(resultSet.getObject("account_id")),
                parseUuid(resultSet.getObject("bank_account_uuid")),
                resultSet.getString("government_name"),
                resultSet.getString("bank_account_name"),
                timestampMillis(resultSet, "created_at"),
                timestampMillis(resultSet, "updated_at")
        );
    }
}
