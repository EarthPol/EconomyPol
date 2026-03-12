package com.earthpol.economyPol.economy.model;

import java.util.UUID;

public record EnderWalletSnapshot(
        UUID playerUuid,
        long baseUnits,
        OfflineEnderWalletState state,
        Long lastCleanSyncAt
) {}
