package com.earthpol.economyPol.towny.model;

import java.util.UUID;

public record TownyGovernmentBinding(
        UUID governmentUuid,
        TownyGovernmentType governmentType,
        UUID accountId,
        UUID bankAccountUuid,
        String governmentName,
        String bankAccountName,
        long createdAt,
        long updatedAt
) {
}
