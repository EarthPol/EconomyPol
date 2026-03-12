package com.earthpol.economyPol.model;

import java.util.UUID;

public record AccountRecord(
        UUID accountId,
        AccountType accountType,
        UUID ownerUuid,
        String accountName,
        PlayerAccountPolicy playerPolicy
) {}
