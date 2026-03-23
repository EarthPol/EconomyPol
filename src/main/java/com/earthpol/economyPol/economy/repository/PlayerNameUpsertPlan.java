package com.earthpol.economyPol.economy.repository;

import java.util.UUID;

record PlayerNameUpsertPlan(String storedName, boolean overwriteExisting) {

    static PlayerNameUpsertPlan from(UUID playerUuid, String requestedName) {
        if (requestedName == null || requestedName.isBlank()) {
            return new PlayerNameUpsertPlan(playerUuid.toString(), false);
        }
        return new PlayerNameUpsertPlan(requestedName, true);
    }
}
