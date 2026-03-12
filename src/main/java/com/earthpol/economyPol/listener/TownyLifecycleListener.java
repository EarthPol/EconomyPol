package com.earthpol.economyPol.listener;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.model.AccountRecord;
import com.earthpol.economyPol.service.EconomyService;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.event.DeleteNationEvent;
import com.palmergames.bukkit.towny.event.DeleteTownEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Optional;
import java.util.UUID;

public final class TownyLifecycleListener implements Listener {

    private static final int TOWNY_ACCOUNT_NAME_MAX_LENGTH = 32;

    private final EconomyService economyService;
    private final EnhancedLogger operationsLog;

    public TownyLifecycleListener(EconomyService economyService, EnhancedLogger operationsLog) {
        this.economyService = economyService;
        this.operationsLog = operationsLog;
    }

    @EventHandler
    public void onDeleteTown(DeleteTownEvent event) {
        deleteGovernmentAccount(
                "town",
                TownyEconomyHandler.modifyNPCUUID(event.getTownUUID()),
                townBankAccountName(event.getTownName())
        );
    }

    @EventHandler
    public void onDeleteNation(DeleteNationEvent event) {
        deleteGovernmentAccount(
                "nation",
                TownyEconomyHandler.modifyNPCUUID(event.getNationUUID()),
                nationBankAccountName(event.getNationName())
        );
    }

    private void deleteGovernmentAccount(String type, UUID accountId, String accountName) {
        if (economyService.deleteSharedAccount(accountId)) {
            operationsLog.info("towny-delete-cleanup type=" + type + " account=" + accountId + " name=" + accountName);
            return;
        }

        Optional<AccountRecord> fallbackByName = economyService.findSharedAccount(accountName);
        if (fallbackByName.isPresent()) {
            boolean deleted = economyService.deleteSharedAccount(fallbackByName.get().accountId());
            if (deleted) {
                operationsLog.warn("towny-delete-cleanup-fallback type=" + type +
                        " requested_account=" + accountId +
                        " deleted_account=" + fallbackByName.get().accountId() +
                        " name=" + accountName);
                return;
            }
        }

        operationsLog.warn("Towny deleted a " + type + " but EconomyPol could not find a matching shared account. " +
                "account=" + accountId + " name=" + accountName);
    }

    private String townBankAccountName(String townName) {
        return trimTownyAccountName(TownySettings.getTownAccountPrefix() + townName);
    }

    private String nationBankAccountName(String nationName) {
        return trimTownyAccountName(TownySettings.getNationAccountPrefix() + nationName);
    }

    private String trimTownyAccountName(String accountName) {
        if (accountName == null) {
            return "";
        }
        return accountName.length() <= TOWNY_ACCOUNT_NAME_MAX_LENGTH
                ? accountName
                : accountName.substring(0, TOWNY_ACCOUNT_NAME_MAX_LENGTH);
    }
}
