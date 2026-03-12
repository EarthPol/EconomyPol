package com.earthpol.economyPol.towny.model;

import java.util.UUID;

public record TownyGovernmentBinding(
        UUID townyBindingId,
        UUID accountId,
        TownyGovernmentType governmentType,
        UUID governmentUuid,
        UUID bankAccountUuid,
        String governmentName,
        String bankAccountName,
        long createdAt,
        long updatedAt
) {
}
