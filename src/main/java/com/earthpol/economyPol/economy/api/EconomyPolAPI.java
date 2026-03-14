package com.earthpol.economyPol.economy.api;

import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.model.Denomination;
import com.earthpol.economyPol.economy.model.EnderWalletSnapshot;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import com.earthpol.economyPol.economy.model.PlayerBalanceView;
import com.earthpol.economyPol.economy.model.ReservationRecord;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

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
    static Optional<EconomyPolAPI> resolve() {
        return Optional.ofNullable(Bukkit.getServicesManager().load(EconomyPolAPI.class));
    }

    AccountRecord ensurePlayerAccount(UUID playerUuid, String playerName);

    Optional<AccountRecord> findAccount(UUID accountId);

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

    PlayerBalanceView getPlayerBalanceView(UUID playerUuid);

    long getBalance(UUID accountId);

    long getCustodialAvailable(UUID playerUuid);

    boolean hasEnough(UUID accountId, long amount);

    MoneyOperationResult depositAccount(UUID accountId, long amount, String reason);

    MoneyOperationResult withdrawAccount(UUID accountId, long amount, String reason);

    MoneyOperationResult depositLive(Player player, long amount);

    MoneyOperationResult withdrawCustodialAsPhysicalMoney(Player player, long amount, List<MoneyRouteTarget> routingOrder);

    BalanceRecord creditCustodial(UUID playerUuid, String playerName, long amount, String reason);

    ReservationRecord reserve(UUID accountId, long amount, String reason, Long expiresAt);

    boolean releaseReservation(UUID reservationId);

    boolean captureReservation(UUID reservationId);

    Optional<EnderWalletSnapshot> getEnderWalletSnapshot(UUID playerUuid);

    MoneyOperationResult debitOfflineEnderWallet(UUID playerUuid, long amount);

    MoneyOperationResult creditOfflineEnderWallet(UUID playerUuid, long amount);

    void snapshotManagedEnderWallet(Player player);

    void syncManagedEnderWallet(Player player);

    void normalizeManagedEnderWallet(Player player);

    boolean isPlayerMoneyLocked(UUID playerUuid);

    List<Denomination> denominations();

    Optional<Denomination> findDenomination(Material material);

    boolean isMoney(ItemStack itemStack);

    long valueOf(ItemStack itemStack);

    long countValue(Iterable<ItemStack> itemStacks);

    List<ItemStack> materialize(long amount);

    String format(long amount);
}
