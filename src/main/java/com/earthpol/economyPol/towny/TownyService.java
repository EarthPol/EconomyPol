package com.earthpol.economyPol.towny;

import com.earthpol.economyPol.towny.model.TownyAccountScanResult;
import com.earthpol.economyPol.towny.model.TownyCleanupResult;

import java.util.UUID;

/**
 * Central coordinator for all Towny integration.
 */
public final class TownyService {

    private volatile Backend backend;

    public TownyService() {
        this.backend = new UnavailableBackend("Towny integration is unavailable because Towny is not enabled.");
    }

    public boolean isAvailable() {
        return backend.isAvailable();
    }

    public void activate(Backend backend) {
        this.backend = backend == null
                ? new UnavailableBackend("Towny integration is unavailable because no backend is registered.")
                : backend;
    }

    public void synchronizeAllGovernments() {
        backend.synchronizeAllGovernments();
    }

    public void refreshTown(UUID townUuid) {
        backend.refreshTown(townUuid);
    }

    public void refreshNation(UUID nationUuid) {
        backend.refreshNation(nationUuid);
    }

    public void deleteTown(UUID townUuid, String townName) {
        backend.deleteTown(townUuid, townName);
    }

    public void deleteNation(UUID nationUuid, String nationName) {
        backend.deleteNation(nationUuid, nationName);
    }

    public TownyAccountScanResult scanAccounts() {
        return backend.scanAccounts();
    }

    public TownyCleanupResult cleanupOrphanedAccounts() {
        return backend.cleanupOrphanedAccounts();
    }

    public interface Backend {
        boolean isAvailable();

        void synchronizeAllGovernments();

        void refreshTown(UUID townUuid);

        void refreshNation(UUID nationUuid);

        void deleteTown(UUID townUuid, String townName);

        void deleteNation(UUID nationUuid, String nationName);

        TownyAccountScanResult scanAccounts();

        TownyCleanupResult cleanupOrphanedAccounts();
    }

    private record UnavailableBackend(String message) implements Backend {

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public void synchronizeAllGovernments() {
        }

        @Override
        public void refreshTown(UUID townUuid) {
        }

        @Override
        public void refreshNation(UUID nationUuid) {
        }

        @Override
        public void deleteTown(UUID townUuid, String townName) {
        }

        @Override
        public void deleteNation(UUID nationUuid, String nationName) {
        }

        @Override
        public TownyAccountScanResult scanAccounts() {
            return TownyAccountScanResult.unavailable(message);
        }

        @Override
        public TownyCleanupResult cleanupOrphanedAccounts() {
            return TownyCleanupResult.unavailable(message);
        }
    }
}
