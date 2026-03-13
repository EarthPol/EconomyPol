package com.earthpol.economyPol.economy.service.support;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.model.ReservationRecord;
import com.earthpol.economyPol.economy.model.ReservationStatus;
import com.earthpol.economyPol.economy.repository.FundsRepository;

import java.util.Optional;
import java.util.UUID;

public final class ReservationService {

    private final FundsRepository repository;
    private final EnhancedLogger auditLogger;

    public ReservationService(FundsRepository repository, EnhancedLogger auditLogger) {
        this.repository = repository;
        this.auditLogger = auditLogger;
    }

    public ReservationRecord reserve(UUID accountId, long amount, String reason, Long expiresAt) {
        repository.reserveAvailable(accountId, amount, reason);
        ReservationRecord reservation = repository.createReservation(accountId, amount, reason, expiresAt);
        auditLogger.info("reservation-active id=" + reservation.reservationId() + " account=" + accountId + " amount=" + amount);
        return reservation;
    }

    public boolean release(UUID reservationId) {
        Optional<ReservationRecord> reservation = repository.findReservation(reservationId);
        if (reservation.isEmpty() || reservation.get().status() != ReservationStatus.ACTIVE) {
            return false;
        }
        repository.releaseReserved(reservation.get().accountId(), reservation.get().amount(), reservation.get().reason());
        repository.updateReservationStatus(reservationId, ReservationStatus.RELEASED);
        return true;
    }

    public boolean capture(UUID reservationId) {
        Optional<ReservationRecord> reservation = repository.findReservation(reservationId);
        if (reservation.isEmpty() || reservation.get().status() != ReservationStatus.ACTIVE) {
            return false;
        }
        repository.captureReserved(reservation.get().accountId(), reservation.get().amount(), reservation.get().reason());
        repository.updateReservationStatus(reservationId, ReservationStatus.CAPTURED);
        return true;
    }
}


