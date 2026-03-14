package com.earthpol.economyPol.economy.service.money;

import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Supplier;

/**
 * Public facade for live physical money behavior. Focused helper services own
 * snapshotting, delivery/materialization, and spending internals.
 */
public final class LiveMoneyService {

    public static final String NOT_ENOUGH_ROOM_FOR_CHANGE_MESSAGE = "Not enough room to return change.";

    private final LiveMoneySnapshotService snapshotService;
    private final LiveMoneyDeliveryService deliveryService;
    private final LiveMoneySpendingService spendingService;

    public LiveMoneyService(DenominationService denominationService, PluginSettings settings) {
        this(denominationService, settings::wallet);
    }

    public LiveMoneyService(DenominationService denominationService, PluginSettings.WalletSettings walletSettings) {
        this(denominationService, () -> walletSettings);
    }

    private LiveMoneyService(
            DenominationService denominationService,
            Supplier<PluginSettings.WalletSettings> walletSettingsSupplier
    ) {
        this.snapshotService = new LiveMoneySnapshotService(denominationService, walletSettingsSupplier);
        this.deliveryService = new LiveMoneyDeliveryService(denominationService, snapshotService);
        this.spendingService = new LiveMoneySpendingService(
                denominationService,
                walletSettingsSupplier,
                snapshotService,
                deliveryService
        );
    }

    public long scanPlayerMoney(Player player) {
        return snapshotService.scanPlayerMoney(player);
    }

    public long countTopLevelEnderChest(Player player) {
        return snapshotService.countTopLevelEnderChest(player);
    }

    public ManagedEnderWalletSyncPlan planManagedEnderWalletSync(ItemStack[] currentContents, long targetBaseUnits) {
        return deliveryService.planManagedEnderWalletSync(currentContents, targetBaseUnits);
    }

    public long removeFromLiveSources(Player player, long amount) {
        return spendingService.removeFromLiveSources(player, amount);
    }

    public SpendabilityResult canSpendFromLiveSources(
            Player player,
            long amount,
            List<MoneyRouteTarget> routingOrder,
            PluginSettings.ChangeOverflowPolicy changeOverflowPolicy
    ) {
        return spendingService.canSpendFromLiveSources(player, amount, routingOrder, changeOverflowPolicy);
    }

    public SpendabilityResult canSpendFromSnapshot(
            LiveContainerSnapshot snapshot,
            long amount,
            List<MoneyRouteTarget> routingOrder,
            PluginSettings.ChangeOverflowPolicy changeOverflowPolicy
    ) {
        return spendingService.canSpendFromSnapshot(snapshot, amount, routingOrder, changeOverflowPolicy);
    }

    public SpendResult spendFromLiveSources(
            Player player,
            long amount,
            List<MoneyRouteTarget> routingOrder,
            PluginSettings.ChangeOverflowPolicy changeOverflowPolicy
    ) {
        return spendingService.spendFromLiveSources(player, amount, routingOrder, changeOverflowPolicy);
    }

    public DeliveryResult deliver(Player player, long amount, List<MoneyRouteTarget> routingOrder) {
        return deliveryService.deliver(player, amount, routingOrder);
    }

    public long maxDeliverableToInventory(Player player, long maxAmount) {
        return deliveryService.maxDeliverableToInventory(snapshotService.captureLiveContainerSnapshot(player), maxAmount);
    }

    public long maxDeliverableToInventory(LiveContainerSnapshot snapshot, long maxAmount) {
        return deliveryService.maxDeliverableToInventory(snapshot, maxAmount);
    }

    public NormalizationResult normalizeEnderChest(Player player) {
        return deliveryService.normalizeEnderChest(player);
    }

    public LiveContainerSnapshot captureLiveContainerSnapshot(Player player) {
        return snapshotService.captureLiveContainerSnapshot(player);
    }

    public void restoreLiveContainerSnapshot(Player player, LiveContainerSnapshot snapshot) {
        snapshotService.restoreLiveContainerSnapshot(player, snapshot);
    }

    public record DeliveryResult(long deliveredToInventory, long deliveredToEnder, long remainder) {}

    public record NormalizationResult(long normalizedValue, long overflow, boolean malformedStacksFound) {}

    public record ManagedEnderWalletSyncPlan(
            long targetBaseUnits,
            long existingTopLevelMoneyValue,
            long overflow,
            boolean malformedStacksFound,
            ItemStack[] targetContents
    ) {}

    public record SpendResult(
            boolean success,
            long requestedAmount,
            long debitedAmount,
            long changeAmount,
            long changeRoutedToCustodial,
            String message
    ) {

        public static SpendResult success(long requestedAmount, long debitedAmount, long changeAmount) {
            return new SpendResult(true, requestedAmount, debitedAmount, changeAmount, 0L, "Funds withdrawn.");
        }

        public static SpendResult success(long requestedAmount, long debitedAmount, long changeAmount, long changeRoutedToCustodial) {
            return new SpendResult(true, requestedAmount, debitedAmount, changeAmount, changeRoutedToCustodial, "Funds withdrawn.");
        }

        public static SpendResult failure(long requestedAmount, String message) {
            return new SpendResult(false, requestedAmount, 0L, 0L, 0L, message);
        }
    }

    public record LiveContainerSnapshot(ItemStack[] inventoryContents, ItemStack[] enderChestContents, ItemStack offHand) {}

    public record SpendabilityResult(boolean success, String message) {

        public static SpendabilityResult allowed() {
            return new SpendabilityResult(true, "Funds available.");
        }

        public static SpendabilityResult blocked(String message) {
            return new SpendabilityResult(false, message);
        }
    }
}
