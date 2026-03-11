package com.earthpol.economyPol.service;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.EconomyPol;
import com.earthpol.economyPol.domain.BalanceRecord;
import com.earthpol.economyPol.domain.EnderWalletSnapshot;
import com.earthpol.economyPol.domain.MoneyOperationResult;
import com.earthpol.economyPol.domain.MoneyRouteTarget;
import com.earthpol.economyPol.domain.OfflineEnderWalletState;
import com.earthpol.economyPol.persistence.JdbcEconomyRepository;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class EnderWalletService {

    private final EconomyPol plugin;
    private final JdbcEconomyRepository repository;
    private final PlayerMoneyLockService playerMoneyLockService;
    private final NotificationService notificationService;
    private final SchedulerService schedulerService;
    private final EnhancedLogger auditLogger;
    private final boolean uncleanBoot;

    public EnderWalletService(
            EconomyPol plugin,
            JdbcEconomyRepository repository,
            PlayerMoneyLockService playerMoneyLockService,
            NotificationService notificationService,
            SchedulerService schedulerService,
            EnhancedLogger auditLogger,
            boolean uncleanBoot
    ) {
        this.plugin = plugin;
        this.repository = repository;
        this.playerMoneyLockService = playerMoneyLockService;
        this.notificationService = notificationService;
        this.schedulerService = schedulerService;
        this.auditLogger = auditLogger;
        this.uncleanBoot = uncleanBoot;
    }

    public Optional<EnderWalletSnapshot> findSnapshot(UUID playerUuid) {
        Optional<EnderWalletSnapshot> snapshot = repository.findEnderWalletSnapshot(playerUuid);
        if (snapshot.isPresent()) {
            return snapshot;
        }
        if (uncleanBoot) {
            return Optional.of(new EnderWalletSnapshot(playerUuid, 0L, OfflineEnderWalletState.DISABLED_UNCLEAN, null));
        }
        return Optional.empty();
    }

    public void snapshotOnQuit(Player player) {
        if (!plugin.settings().wallet().managedEnderWalletEnabled()) {
            return;
        }
        schedulerService.runOnPlayerEntityScheduler(player, () -> snapshotOnQuitOnPlayerEntityScheduler(player), "ender-wallet-snapshot-on-quit");
    }

    private void snapshotOnQuitOnPlayerEntityScheduler(Player player) {
        long baseUnits = plugin.economyService().liveMoneyService().countTopLevelEnderChest(player);
        repository.upsertEnderWalletSnapshot(new EnderWalletSnapshot(
                player.getUniqueId(),
                baseUnits,
                OfflineEnderWalletState.FROZEN,
                System.currentTimeMillis()
        ));
        auditLogger.info("ender-wallet-freeze player=" + player.getUniqueId() + " amount=" + baseUnits);
    }

    public long syncSnapshotOnJoin(Player player) {
        if (!plugin.settings().wallet().managedEnderWalletEnabled()) {
            return 0L;
        }
        if (!playerMoneyLockService.lock(player.getUniqueId())) {
            auditLogger.warn("ender-wallet-sync-skipped player=" + player.getUniqueId() + " reason=already_locked");
            return 0L;
        }
        Optional<Long> overflow = schedulerService.callOnPlayerEntityScheduler(
                player,
                () -> syncSnapshotOnJoinLocked(player),
                "ender-wallet-sync-on-join"
        );
        if (overflow.isEmpty()) {
            playerMoneyLockService.unlock(player.getUniqueId());
            return 0L;
        }
        return overflow.get();
    }

    private long syncSnapshotOnJoinLocked(Player player) {
        Optional<EnderWalletSnapshot> snapshotOptional = findSnapshot(player.getUniqueId());
        if (snapshotOptional.isEmpty()) {
            playerMoneyLockService.unlock(player.getUniqueId());
            return 0L;
        }
        EnderWalletSnapshot snapshot = snapshotOptional.get();
        if (snapshot.state() == OfflineEnderWalletState.DISABLED_UNCLEAN) {
            auditLogger.warn("ender-wallet-disabled player=" + player.getUniqueId() + " reason=unclean_boot");
            playerMoneyLockService.unlock(player.getUniqueId());
            return 0L;
        }
        if (snapshot.state() != OfflineEnderWalletState.FROZEN) {
            playerMoneyLockService.unlock(player.getUniqueId());
            return 0L;
        }

        try {
            repository.upsertEnderWalletSnapshot(new EnderWalletSnapshot(
                    snapshot.playerUuid(),
                    snapshot.baseUnits(),
                    OfflineEnderWalletState.SYNCING,
                    snapshot.lastCleanSyncAt()
            ));

            LiveMoneyService.NormalizationResult normalization = plugin.economyService().liveMoneyService().normalizeEnderChest(player);
            long targetValue = snapshot.baseUnits() + normalization.normalizedValue();
            LiveMoneyService.DeliveryResult delivery = plugin.economyService().liveMoneyService()
                    .deliver(player, targetValue, List.of(MoneyRouteTarget.ENDER_CHEST));

            long overflow = delivery.remainder() + normalization.overflow();
            if (overflow > 0L) {
                BalanceRecord updatedBalance = plugin.economyService().creditCustodial(
                        player.getUniqueId(),
                        player.getName(),
                        overflow,
                        "ENDER_WALLET_OVERFLOW"
                );
                notificationService.notifyWalletOverflowToCustodial(
                        player,
                        overflow,
                        updatedBalance.availableBalance(),
                        normalization.malformedStacksFound()
                );
            }
            repository.deleteEnderWalletSnapshot(player.getUniqueId());
            if (normalization.malformedStacksFound()) {
                plugin.log().warn("Malformed money stacks were found for " + player.getName() + ". Overflow moved to custodial.");
            }
            auditLogger.info("ender-wallet-sync player=" + player.getUniqueId() + " amount=" + targetValue + " overflow=" + overflow);
            return overflow;
        } finally {
            playerMoneyLockService.unlock(player.getUniqueId());
        }
    }

    public void normalizeOnlineEnderWallet(Player player) {
        schedulerService.runOnPlayerEntityScheduler(player, () -> normalizeOnlineEnderWalletOnPlayerEntityScheduler(player), "ender-wallet-normalize");
    }

    private void normalizeOnlineEnderWalletOnPlayerEntityScheduler(Player player) {
        LiveMoneyService.NormalizationResult normalization = plugin.economyService().liveMoneyService().normalizeEnderChest(player);
        if (normalization.overflow() > 0L) {
            BalanceRecord updatedBalance = plugin.economyService().creditCustodial(
                    player.getUniqueId(),
                    player.getName(),
                    normalization.overflow(),
                    "ENDER_WALLET_NORMALIZE_OVERFLOW"
            );
            notificationService.notifyWalletOverflowToCustodial(
                    player,
                    normalization.overflow(),
                    updatedBalance.availableBalance(),
                    normalization.malformedStacksFound()
            );
        }
        auditLogger.info("ender-wallet-normalize player=" + player.getUniqueId() + " normalized=" +
                normalization.normalizedValue() + " overflow=" + normalization.overflow());
    }

    public MoneyOperationResult debitOffline(UUID playerUuid, long amount) {
        Optional<EnderWalletSnapshot> snapshotOptional = findSnapshot(playerUuid);
        if (snapshotOptional.isEmpty()) {
            return MoneyOperationResult.failure(amount, "No offline ender-wallet snapshot.");
        }
        EnderWalletSnapshot snapshot = snapshotOptional.get();
        if (snapshot.state() != OfflineEnderWalletState.FROZEN || snapshot.baseUnits() <= 0L) {
            return MoneyOperationResult.failure(amount, "Offline ender wallet unavailable.");
        }
        long debited = Math.min(snapshot.baseUnits(), amount);
        repository.upsertEnderWalletSnapshot(new EnderWalletSnapshot(
                playerUuid,
                snapshot.baseUnits() - debited,
                snapshot.state(),
                snapshot.lastCleanSyncAt()
        ));
        auditLogger.info("ender-wallet-debit player=" + playerUuid + " amount=" + debited);
        return MoneyOperationResult.success(amount, debited, amount - debited, "Offline ender wallet debited.");
    }

    public MoneyOperationResult creditOffline(UUID playerUuid, long amount) {
        Optional<EnderWalletSnapshot> snapshotOptional = findSnapshot(playerUuid);
        if (snapshotOptional.isEmpty()) {
            return MoneyOperationResult.failure(amount, "Offline ender wallet unavailable.");
        }
        EnderWalletSnapshot snapshot = snapshotOptional.get();
        if (snapshot.state() != OfflineEnderWalletState.FROZEN) {
            return MoneyOperationResult.failure(amount, "Offline ender wallet unavailable.");
        }
        repository.upsertEnderWalletSnapshot(new EnderWalletSnapshot(
                playerUuid,
                snapshot.baseUnits() + amount,
                snapshot.state(),
                snapshot.lastCleanSyncAt()
        ));
        auditLogger.info("ender-wallet-credit player=" + playerUuid + " amount=" + amount);
        return MoneyOperationResult.success(amount, amount, 0L, "Offline ender wallet credited.");
    }
}
