package com.earthpol.economyPol.economy.repository;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PlayerNameUpsertPlanTest {

    @Test
    void realNameOverwritesExistingStoredValue() {
        UUID playerUuid = UUID.randomUUID();

        PlayerNameUpsertPlan plan = PlayerNameUpsertPlan.from(playerUuid, "Bustun");

        assertEquals("Bustun", plan.storedName());
        assertTrue(plan.overwriteExisting());
    }

    @Test
    void missingNameFallsBackToUuidWithoutOverwritingExistingStoredValue() {
        UUID playerUuid = UUID.randomUUID();

        PlayerNameUpsertPlan plan = PlayerNameUpsertPlan.from(playerUuid, null);

        assertEquals(playerUuid.toString(), plan.storedName());
        assertFalse(plan.overwriteExisting());
    }

    @Test
    void blankNameIsTreatedAsMissing() {
        UUID playerUuid = UUID.randomUUID();

        PlayerNameUpsertPlan plan = PlayerNameUpsertPlan.from(playerUuid, "   ");

        assertEquals(playerUuid.toString(), plan.storedName());
        assertFalse(plan.overwriteExisting());
    }
}
