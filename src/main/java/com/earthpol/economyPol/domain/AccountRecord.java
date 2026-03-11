package com.earthpol.economyPol.domain;

import java.util.UUID;

public record AccountRecord(
        UUID accountId,
        AccountType accountType,
        UUID ownerUuid,
        String accountName,
        PlayerAccountPolicy playerPolicy
) {}
