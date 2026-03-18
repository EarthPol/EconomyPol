package com.earthpol.economyPol.economy.service.support;

import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.logging.EconomyLoggers.LogType;
import com.earthpol.economyPol.economy.model.ReservationRecord;
import com.earthpol.economyPol.economy.model.ReservationStatus;
import com.earthpol.economyPol.economy.repository.FundsRepository;

import java.util.Optional;
import java.util.UUID;

public final class ReservationService {

    private final FundsRepository repository;
    private final EconomyLoggers loggers;

    public ReservationService(FundsRepository repository, EconomyLoggers loggers) {
        this.repository = repository;
        this.loggers = loggers;
    }

    public ReservationRecord reserve(UUID accountId, long amount, String reason, Long expiresAt) {
        repository.reserveAvailable(accountId, amount, reason);
        ReservationRecord reservation = repository.createReservation(accountId, amount, reason, expiresAt);
        loggers.log("reservation-active id=" + reservation.reservationId() + " account=" + accountId + " amount=" + amount,
                LogType.AUDIT);
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


