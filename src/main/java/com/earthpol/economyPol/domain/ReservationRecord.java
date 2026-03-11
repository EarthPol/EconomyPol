package com.earthpol.economyPol.domain;

import java.util.UUID;

public record ReservationRecord(
        UUID reservationId,
        UUID accountId,
        long amount,
        ReservationStatus status,
        String reason,
        Long expiresAt
) {}
