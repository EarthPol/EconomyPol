package com.earthpol.economyPol.economy.service.databasecheck;

import com.earthpol.economyPol.economy.model.DatabaseCheckReport;

import java.time.Instant;

interface DatabaseCheckRunner {

    String reportName();

    DatabaseCheckReport run(Instant ranAt, long startedNanos);
}
