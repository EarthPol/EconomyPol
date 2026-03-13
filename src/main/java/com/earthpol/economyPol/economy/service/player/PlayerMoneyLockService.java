package com.earthpol.economyPol.economy.service.player;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PlayerMoneyLockService {

    private final Set<UUID> lockedPlayers = ConcurrentHashMap.newKeySet();

    public boolean lock(UUID playerUuid) {
        return lockedPlayers.add(playerUuid);
    }

    public void unlock(UUID playerUuid) {
        lockedPlayers.remove(playerUuid);
    }

    public boolean isLocked(UUID playerUuid) {
        return lockedPlayers.contains(playerUuid);
    }
}


