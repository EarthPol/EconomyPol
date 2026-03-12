package com.earthpol.economyPol.service;

public final class TownyDiagnosticsService {

    private volatile Backend backend;

    public TownyDiagnosticsService() {
        this.backend = new UnavailableBackend("Towny diagnostics are unavailable because Towny is not enabled.");
    }

    public boolean isAvailable() {
        return backend.isAvailable();
    }

    public TownyAccountScanResult scanAccounts() {
        return backend.scanAccounts();
    }

    public TownyCleanupResult cleanupOrphanedAccounts() {
        return backend.cleanupOrphanedAccounts();
    }

    public void setBackend(Backend backend) {
        this.backend = backend == null
                ? new UnavailableBackend("Towny diagnostics are unavailable because no backend is registered.")
                : backend;
    }

    public interface Backend {
        boolean isAvailable();

        TownyAccountScanResult scanAccounts();

        TownyCleanupResult cleanupOrphanedAccounts();
    }

    private record UnavailableBackend(String message) implements Backend {

        @Override
        public boolean isAvailable() {
            return false;
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
