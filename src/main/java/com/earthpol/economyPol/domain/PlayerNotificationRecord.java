package com.earthpol.economyPol.domain;

import java.util.UUID;

public record PlayerNotificationRecord(
        long notificationId,
        UUID playerUuid,
        PlayerNotificationType notificationType,
        Long primaryAmount,
        Long secondaryAmount,
        String detailText,
        boolean flagValue,
        long createdAt
) {}
