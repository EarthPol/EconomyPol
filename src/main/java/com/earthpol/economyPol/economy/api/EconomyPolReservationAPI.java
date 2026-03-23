package com.earthpol.economyPol.economy.api;

import com.earthpol.economyPol.economy.model.ReservationRecord;

import java.util.UUID;

public interface EconomyPolReservationAPI {

    ReservationRecord reserve(UUID accountId, long amount, String reason, Long expiresAt);

    boolean releaseReservation(UUID reservationId);

    boolean captureReservation(UUID reservationId);
}
