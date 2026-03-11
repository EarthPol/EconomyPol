package com.earthpol.economyPol.domain;

import java.util.UUID;

public record EnderWalletSnapshot(
        UUID playerUuid,
        long baseUnits,
        OfflineEnderWalletState state,
        Long lastCleanSyncAt
) {}
