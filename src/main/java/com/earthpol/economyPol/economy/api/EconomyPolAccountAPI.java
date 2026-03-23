package com.earthpol.economyPol.economy.api;

import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface EconomyPolAccountAPI {

    void registerPlayer(UUID playerUuid, String playerName);

    AccountRecord ensurePlayerAccount(UUID playerUuid, String playerName);

    Optional<AccountRecord> findAccount(UUID accountId);

    Optional<AccountRecord> findPlayerAccount(UUID playerUuid);

    Optional<AccountRecord> findAccountByName(String name);

    Optional<String> getAccountName(UUID accountId);

    Map<UUID, String> getAccountNameMap();

    boolean createSharedAccount(UUID accountId, String name, UUID ownerUuid);

    boolean renameAccount(UUID accountId, String name);

    boolean deleteSharedAccount(UUID accountId);

    boolean isAccountOwner(UUID accountId, UUID subjectUuid);

    boolean setSharedAccountOwner(UUID accountId, UUID ownerUuid);

    boolean isAccountMember(UUID accountId, UUID subjectUuid);

    boolean addSharedAccountMember(UUID accountId, UUID memberUuid);

    boolean removeSharedAccountMember(UUID accountId, UUID memberUuid);

    long getSharedAccountBalance(UUID accountId);

    boolean sharedAccountHasEnough(UUID accountId, long amount);

    MoneyOperationResult depositToSharedAccount(UUID accountId, long amount, String reason);

    MoneyOperationResult withdrawFromSharedAccount(UUID accountId, long amount, String reason);
}
