package com.earthpol.economyPol.economy.api;

import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.model.Denomination;
import com.earthpol.economyPol.economy.model.EnderWalletSnapshot;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.model.PlayerBalanceView;
import com.earthpol.economyPol.economy.model.ReservationRecord;
import com.earthpol.economyPol.economy.service.DenominationService;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.EnderWalletService;
import com.earthpol.economyPol.economy.service.ReservationService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class EconomyPolApiProvider implements EconomyPolAPI {

    private final EconomyService economyService;
    private final ReservationService reservationService;
    private final EnderWalletService enderWalletService;
    private final DenominationService denominationService;

    public EconomyPolApiProvider(
            EconomyService economyService,
            ReservationService reservationService,
            EnderWalletService enderWalletService,
            DenominationService denominationService
    ) {
        this.economyService = economyService;
        this.reservationService = reservationService;
        this.enderWalletService = enderWalletService;
        this.denominationService = denominationService;
    }

    @Override
    public AccountRecord ensurePlayerAccount(UUID playerUuid, String playerName) {
        return economyService.ensurePlayerAccount(playerUuid, playerName);
    }

    @Override
    public Optional<AccountRecord> findAccount(UUID accountId) {
        return economyService.findAccount(accountId);
    }

    @Override
    public Optional<AccountRecord> findAccountByName(String name) {
        return economyService.findAccountByName(name);
    }

    @Override
    public Optional<String> getAccountName(UUID accountId) {
        return economyService.getAccountName(accountId);
    }

    @Override
    public Map<UUID, String> getAccountNameMap() {
        return economyService.accountNameMap();
    }

    @Override
    public boolean createSharedAccount(UUID accountId, String name, UUID ownerUuid) {
        return economyService.createSharedAccount(accountId, name, ownerUuid);
    }

    @Override
    public boolean renameAccount(UUID accountId, String name) {
        return economyService.renameAccount(accountId, name);
    }

    @Override
    public boolean deleteSharedAccount(UUID accountId) {
        return economyService.deleteSharedAccount(accountId);
    }

    @Override
    public boolean isAccountOwner(UUID accountId, UUID subjectUuid) {
        return economyService.isAccountOwner(accountId, subjectUuid);
    }

    @Override
    public boolean setSharedAccountOwner(UUID accountId, UUID ownerUuid) {
        return economyService.setSharedAccountOwner(accountId, ownerUuid);
    }

    @Override
    public boolean isAccountMember(UUID accountId, UUID subjectUuid) {
        return economyService.isAccountMember(accountId, subjectUuid);
    }

    @Override
    public boolean addSharedAccountMember(UUID accountId, UUID memberUuid) {
        return economyService.addSharedAccountMember(accountId, memberUuid);
    }

    @Override
    public boolean removeSharedAccountMember(UUID accountId, UUID memberUuid) {
        return economyService.removeSharedAccountMember(accountId, memberUuid);
    }

    @Override
    public PlayerBalanceView getPlayerBalanceView(UUID playerUuid) {
        return economyService.balanceView(Bukkit.getOfflinePlayer(playerUuid));
    }

    @Override
    public long getBalance(UUID accountId) {
        return economyService.getBalance(accountId);
    }

    @Override
    public long getCustodialAvailable(UUID playerUuid) {
        return economyService.getCustodialAvailable(Bukkit.getOfflinePlayer(playerUuid));
    }

    @Override
    public boolean hasEnough(UUID accountId, long amount) {
        return economyService.hasEnough(accountId, amount);
    }

    @Override
    public MoneyOperationResult depositAccount(UUID accountId, long amount, String reason) {
        return economyService.depositAccount(accountId, amount, reason);
    }

    @Override
    public MoneyOperationResult withdrawAccount(UUID accountId, long amount, String reason) {
        return economyService.withdrawAccount(accountId, amount, reason);
    }

    @Override
    public MoneyOperationResult depositLive(Player player, long amount) {
        return economyService.depositSelf(player, amount);
    }

    @Override
    public MoneyOperationResult withdrawCustodialAsPhysicalMoney(Player player, long amount) {
        return economyService.withdrawCustodialAsPhysicalMoney(player, amount);
    }

    @Override
    public BalanceRecord creditCustodial(UUID playerUuid, String playerName, long amount, String reason) {
        return economyService.creditCustodial(playerUuid, playerName, amount, reason);
    }

    @Override
    public ReservationRecord reserve(UUID accountId, long amount, String reason, Long expiresAt) {
        return reservationService.reserve(accountId, amount, reason, expiresAt);
    }

    @Override
    public boolean releaseReservation(UUID reservationId) {
        return reservationService.release(reservationId);
    }

    @Override
    public boolean captureReservation(UUID reservationId) {
        return reservationService.capture(reservationId);
    }

    @Override
    public Optional<EnderWalletSnapshot> getEnderWalletSnapshot(UUID playerUuid) {
        return enderWalletService.findSnapshot(playerUuid);
    }

    @Override
    public MoneyOperationResult debitOfflineEnderWallet(UUID playerUuid, long amount) {
        return enderWalletService.debitOffline(playerUuid, amount);
    }

    @Override
    public MoneyOperationResult creditOfflineEnderWallet(UUID playerUuid, long amount) {
        return enderWalletService.creditOffline(playerUuid, amount);
    }

    @Override
    public void snapshotManagedEnderWallet(Player player) {
        enderWalletService.snapshotOnQuit(player);
    }

    @Override
    public void syncManagedEnderWallet(Player player) {
        enderWalletService.syncSnapshotOnJoin(player);
    }

    @Override
    public void normalizeManagedEnderWallet(Player player) {
        enderWalletService.normalizeOnlineEnderWallet(player);
    }

    @Override
    public boolean isPlayerMoneyLocked(UUID playerUuid) {
        return economyService.isPlayerLocked(playerUuid);
    }

    @Override
    public List<Denomination> denominations() {
        return denominationService.descending().reversed();
    }

    @Override
    public Optional<Denomination> findDenomination(Material material) {
        return denominationService.find(material);
    }

    @Override
    public boolean isMoney(ItemStack itemStack) {
        return denominationService.isMoney(itemStack);
    }

    @Override
    public long valueOf(ItemStack itemStack) {
        return denominationService.valueOf(itemStack);
    }

    @Override
    public long countValue(Iterable<ItemStack> itemStacks) {
        return denominationService.countStacks(itemStacks);
    }

    @Override
    public List<ItemStack> materialize(long amount) {
        return denominationService.materialize(amount);
    }

    @Override
    public String format(long amount) {
        return denominationService.format(amount);
    }
}
