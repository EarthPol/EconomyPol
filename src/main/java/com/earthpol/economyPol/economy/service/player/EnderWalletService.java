package com.earthpol.economyPol.economy.service.player;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.EconomyPol;
import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.model.EnderWalletSnapshot;
import com.earthpol.economyPol.economy.model.MoneyOperationFailureReason;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.model.OfflineEnderWalletState;
import com.earthpol.economyPol.economy.repository.EnderWalletRepository;
import com.earthpol.economyPol.economy.service.money.LiveMoneyService;
import com.earthpol.economyPol.economy.service.support.SchedulerService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class EnderWalletService {

    private final EconomyPol plugin;
    private final EnderWalletRepository repository;
    private final PlayerMoneyLockService playerMoneyLockService;
    private final NotificationService notificationService;
    private final SchedulerService schedulerService;
    private final EnhancedLogger auditLogger;
    private final boolean uncleanBoot;

    public EnderWalletService(
            EconomyPol plugin,
            EnderWalletRepository repository,
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

    public List<EnderWalletSnapshot> listFrozenSnapshots() {
        return repository.listFrozenEnderWalletSnapshots();
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
        try {
            if (snapshotOptional.isEmpty()) {
                return 0L;
            }

            EnderWalletSnapshot snapshot = snapshotOptional.get();
            if (snapshot.state() == OfflineEnderWalletState.DISABLED_UNCLEAN) {
                auditLogger.warn("ender-wallet-disabled player=" + player.getUniqueId() + " reason=unclean_boot");
                return 0L;
            }
            if (snapshot.state() != OfflineEnderWalletState.FROZEN) {
                return 0L;
            }

            EnderWalletSnapshot syncingSnapshot = new EnderWalletSnapshot(
                    snapshot.playerUuid(),
                    snapshot.baseUnits(),
                    OfflineEnderWalletState.SYNCING,
                    snapshot.lastCleanSyncAt()
            );
            repository.upsertEnderWalletSnapshot(syncingSnapshot);

            ItemStack[] originalContents = cloneContents(player.getEnderChest().getContents());
            LiveMoneyService.ManagedEnderWalletSyncPlan plan = plugin.economyService()
                    .liveMoneyService()
                    .planManagedEnderWalletSync(originalContents, snapshot.baseUnits());

            try {
                player.getEnderChest().setContents(cloneContents(plan.targetContents()));

                long overflow = plan.overflow();
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
                            plan.malformedStacksFound()
                    );
                }
                repository.deleteEnderWalletSnapshot(player.getUniqueId());
                if (plan.malformedStacksFound()) {
                    plugin.log().warn("Malformed money stacks were found for " + player.getName() + ". Overflow moved to custodial.");
                }
                auditLogger.info("ender-wallet-sync player=" + player.getUniqueId() +
                        " snapshot_amount=" + snapshot.baseUnits() +
                        " existing_top_level_money=" + plan.existingTopLevelMoneyValue() +
                        " overflow=" + overflow);
                return overflow;
            } catch (Exception exception) {
                rollbackJoinSync(player, snapshot, originalContents, exception);
                return 0L;
            }
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
            return MoneyOperationResult.failure(
                    amount,
                    "No offline ender-wallet snapshot.",
                    MoneyOperationFailureReason.OFFLINE_ENDER_WALLET_MISSING
            );
        }
        EnderWalletSnapshot snapshot = snapshotOptional.get();
        if (snapshot.state() != OfflineEnderWalletState.FROZEN || snapshot.baseUnits() <= 0L) {
            return MoneyOperationResult.failure(
                    amount,
                    "Offline ender wallet unavailable.",
                    MoneyOperationFailureReason.OFFLINE_ENDER_WALLET_UNAVAILABLE
            );
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
            return MoneyOperationResult.failure(
                    amount,
                    "Offline ender wallet unavailable.",
                    MoneyOperationFailureReason.OFFLINE_ENDER_WALLET_UNAVAILABLE
            );
        }
        EnderWalletSnapshot snapshot = snapshotOptional.get();
        if (snapshot.state() != OfflineEnderWalletState.FROZEN) {
            return MoneyOperationResult.failure(
                    amount,
                    "Offline ender wallet unavailable.",
                    MoneyOperationFailureReason.OFFLINE_ENDER_WALLET_UNAVAILABLE
            );
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

    private void rollbackJoinSync(
            Player player,
            EnderWalletSnapshot frozenSnapshot,
            ItemStack[] originalContents,
            Exception exception
    ) {
        plugin.log().severe("Failed to sync managed ender wallet for " + player.getName() + ".", exception);
        try {
            player.getEnderChest().setContents(cloneContents(originalContents));
        } catch (Exception restoreException) {
            plugin.log().severe("Failed to restore ender chest after sync rollback for " + player.getName() + ".", restoreException);
        }
        try {
            repository.upsertEnderWalletSnapshot(frozenSnapshot);
        } catch (Exception revertException) {
            plugin.log().severe("Failed to restore ender-wallet snapshot state for " + player.getName() + ".", revertException);
        }
        auditLogger.warn("ender-wallet-sync-rollback player=" + player.getUniqueId() +
                " amount=" + frozenSnapshot.baseUnits() +
                " reason=" + exception.getClass().getSimpleName());
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] clone = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            clone[index] = cloneStack(contents[index]);
        }
        return clone;
    }

    private static ItemStack cloneStack(ItemStack itemStack) {
        return itemStack == null ? null : itemStack.clone();
    }
}


