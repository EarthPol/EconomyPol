package com.earthpol.economyPol.economy.api;

import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.model.Denomination;
import com.earthpol.economyPol.economy.model.EnderWalletSnapshot;
import com.earthpol.economyPol.economy.model.IncomingPaymentDeliveryPreference;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import com.earthpol.economyPol.economy.model.PlayerBalanceView;
import com.earthpol.economyPol.economy.model.ReservationRecord;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Native API for EconomyPol.
 *
 * <p>This is the preferred integration surface for plugins that need access to
 * EconomyPol-specific behavior such as custodial balances, reservations,
 * denomination helpers, and managed offline ender-wallet operations.</p>
 */
public interface EconomyPolAPI {

    /**
     * Resolve the currently-registered EconomyPol API service.
     */
    static Optional<EconomyPolAPI> getInstance(Plugin callerPlugin) {
        EconomyPolApiFactory factory = Bukkit.getServicesManager().load(EconomyPolApiFactory.class);
        if (factory == null) {
            return Optional.empty();
        }
        return Optional.of(factory.getInstance(callerPlugin));
    }

    void registerPlayer(UUID playerUuid, String playerName);

    AccountRecord ensurePlayerAccount(UUID playerUuid, String playerName);

    Optional<AccountRecord> findAccount(UUID accountId);

    Optional<AccountRecord> findPlayerAccount(UUID playerUuid);

    Optional<AccountRecord> findAccountByName(String name);

    Optional<String> getAccountName(UUID accountId);

    Map<UUID, String> getAccountNameMap();

    boolean createSharedAccount(UUID accountId, String name, UUID ownerUuid);

    boolean renameAccount(UUID accountId, String name);

    boolean deleteSharedAccount(UUID accountId);

    boolean isAccountOwner(UUID accountId, UUID subjectUuid);

    boolean setSharedAccountOwner(UUID accountId, UUID ownerUuid);

    boolean isAccountMember(UUID accountId, UUID subjectUuid);

    boolean addSharedAccountMember(UUID accountId, UUID memberUuid);

    boolean removeSharedAccountMember(UUID accountId, UUID memberUuid);

    long getSharedAccountBalance(UUID accountId);

    boolean sharedAccountHasEnough(UUID accountId, long amount);

    MoneyOperationResult depositToSharedAccount(UUID accountId, long amount, String reason);

    MoneyOperationResult withdrawFromSharedAccount(UUID accountId, long amount, String reason);

    PlayerBalanceView getPlayerBalanceView(UUID playerUuid);

    long getPlayerSpendableBalance(UUID playerUuid);

    long getCustodialAvailable(UUID playerUuid);

    boolean playerHasEnough(UUID playerUuid, long amount);

    MoneyOperationResult depositToPlayerAccount(UUID playerUuid, long amount, String reason);

    MoneyOperationResult withdrawFromPlayerAccount(UUID playerUuid, long amount, String reason);

    MoneyOperationResult depositPhysicalMoneyToCustodial(Player player, long amount);

    MoneyOperationResult withdrawCustodialAsPhysicalMoney(Player player, long amount, List<MoneyRouteTarget> routingOrder);

    long getMaxWithdrawableCustodialToInventory(Player player);

    MoneyOperationResult withdrawMaxCustodialToInventory(Player player);

    BalanceRecord creditCustodial(UUID playerUuid, String playerName, long amount, String reason);

    IncomingPaymentDeliveryPreference getIncomingPaymentDeliveryPreference(UUID playerUuid);

    IncomingPaymentDeliveryPreference setIncomingPaymentDeliveryPreference(
            UUID playerUuid,
            String playerName,
            IncomingPaymentDeliveryPreference preference
    );

    boolean isPlayerMoneyLocked(UUID playerUuid);

    ReservationRecord reserve(UUID accountId, long amount, String reason, Long expiresAt);

    boolean releaseReservation(UUID reservationId);

    boolean captureReservation(UUID reservationId);

    Optional<EnderWalletSnapshot> getEnderWalletSnapshot(UUID playerUuid);

    MoneyOperationResult debitOfflineEnderWallet(UUID playerUuid, long amount);

    MoneyOperationResult creditOfflineEnderWallet(UUID playerUuid, long amount);

    void snapshotManagedEnderWallet(Player player);

    void syncManagedEnderWallet(Player player);

    void normalizeManagedEnderWallet(Player player);

    List<Denomination> denominations();

    Optional<Denomination> findDenomination(Material material);

    boolean isMoney(ItemStack itemStack);

    long valueOf(ItemStack itemStack);

    long countValue(Iterable<ItemStack> itemStacks);

    List<ItemStack> materialize(long amount);

    String format(long amount);
}
