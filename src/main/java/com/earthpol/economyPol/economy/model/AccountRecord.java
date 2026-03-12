package com.earthpol.economyPol.economy.model;

import java.util.UUID;

public record AccountRecord(
        UUID accountId,
        AccountType accountType,
        UUID ownerUuid,
        String accountName,
        PlayerAccountPolicy playerPolicy
) {}
