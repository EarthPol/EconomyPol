package com.earthpol.economyPol.economy.model;

import java.util.UUID;

public record PendingPlayerPayment(
        UUID pendingPaymentId,
        UUID playerUuid,
        long paymentAmount,
        PendingPlayerPaymentStatus status,
        long createdAt,
        Long lastAttemptedDeliveryAt,
        PendingPlayerPaymentAttemptResult lastAttemptedDeliveryResult
) {}
