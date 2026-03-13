package com.earthpol.economyPol.economy.service.databasecheck;

import com.earthpol.earthPolLib.database.DatabaseService;
import com.earthpol.economyPol.economy.model.DatabaseCheckReport;
import com.earthpol.economyPol.towny.TownyService;
import com.earthpol.economyPol.towny.model.TownyAccountScanResult;

import java.time.Instant;

final class TownyAccountsDatabaseCheckRunner extends AbstractDatabaseCheckRunner {

    TownyAccountsDatabaseCheckRunner(DatabaseService databaseService, TownyService townyService) {
        super(databaseService, townyService);
    }

    @Override
    public String reportName() {
        return "towny-accounts";
    }

    @Override
    public DatabaseCheckReport run(Instant ranAt, long startedNanos) {
        TownyAccountScanResult result = townyService.scanAccounts();
        return finish(
                ranAt,
                startedNanos,
                result.available() && result.findings().isEmpty(),
                result.summary(),
                result.statistics(),
                result.notes(),
                result.findings()
        );
    }
}
