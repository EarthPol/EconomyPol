package com.earthpol.economyPol.api;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.economy.api.EconomyPolAPI;
import com.earthpol.economyPol.economy.api.EconomyPolApiFactory;
import com.earthpol.economyPol.economy.api.EconomyPolApiProvider;
import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.AccountType;
import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.model.Denomination;
import com.earthpol.economyPol.economy.model.EnderWalletSnapshot;
import com.earthpol.economyPol.economy.model.IncomingPaymentDeliveryPreference;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import com.earthpol.economyPol.economy.model.OfflineEnderWalletState;
import com.earthpol.economyPol.economy.model.PlayerBalanceView;
import com.earthpol.economyPol.economy.model.ReservationRecord;
import com.earthpol.economyPol.economy.model.ReservationStatus;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.player.EnderWalletService;
import com.earthpol.economyPol.economy.service.support.ReservationService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockito.ArgumentMatcher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class EconomyPolApiProviderTest {

    private static final List<MoneyRouteTarget> DEFAULT_ROUTING_ORDER = List.of(
            MoneyRouteTarget.INVENTORY,
            MoneyRouteTarget.ENDER_CHEST,
            MoneyRouteTarget.CUSTODIAL_ACCOUNT
    );

    private DenominationService denominationService;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        denominationService = new DenominationService(
                new PluginSettings.CurrencySettings(
                        "Gold Coin",
                        "Gold Coins",
                        List.of(
                                new Denomination(Material.GOLD_NUGGET, 1),
                                new Denomination(Material.GOLD_INGOT, 9),
                                new Denomination(Material.GOLD_BLOCK, 81)
                        )
                ),
                null
        );
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void resolveReturnsRegisteredApiService() {
        EconomyPolApiFactory factory = mock(EconomyPolApiFactory.class);
        EconomyPolAPI api = mock(EconomyPolAPI.class);
        JavaPlugin plugin = MockBukkit.createMockPlugin();
        JavaPlugin callerPlugin = MockBukkit.createMockPlugin();

        when(factory.getInstance(callerPlugin)).thenReturn(api);
        Bukkit.getServicesManager().register(EconomyPolApiFactory.class, factory, plugin, ServicePriority.Highest);

        assertSame(api, EconomyPolAPI.resolve(callerPlugin).orElseThrow());
    }

    @Test
    void playerBalanceAndPreferenceQueriesDelegateUsingResolvedOfflinePlayer() {
        EconomyService economyService = mock(EconomyService.class);
        ReservationService reservationService = mock(ReservationService.class);
        EnderWalletService enderWalletService = mock(EnderWalletService.class);
        EnhancedLogger auditLog = mock(EnhancedLogger.class);
        EconomyPolApiProvider api = new EconomyPolApiProvider(
                economyService,
                reservationService,
                enderWalletService,
                denominationService,
                auditLog
        );
        UUID playerUuid = UUID.randomUUID();
        PlayerBalanceView expectedView = new PlayerBalanceView(125L, 25L, 75L, 10L, false);

        when(economyService.balanceView(argThat(hasUuid(playerUuid)))).thenReturn(expectedView);
        when(economyService.getPlayerSpendableBalance(argThat(hasUuid(playerUuid)))).thenReturn(85L);
        when(economyService.getCustodialAvailable(argThat(hasUuid(playerUuid)))).thenReturn(125L);
        when(economyService.playerHasEnough(argThat(hasUuid(playerUuid)), eq(40L))).thenReturn(true);
        when(economyService.getIncomingPaymentDeliveryPreference(argThat(hasUuid(playerUuid))))
                .thenReturn(IncomingPaymentDeliveryPreference.SKIP_INVENTORY);
        when(economyService.setIncomingPaymentDeliveryPreference(
                playerUuid,
                "Alice",
                IncomingPaymentDeliveryPreference.SKIP_INVENTORY_AND_ENDERCHEST
        )).thenReturn(IncomingPaymentDeliveryPreference.SKIP_INVENTORY_AND_ENDERCHEST);

        assertSame(expectedView, api.getPlayerBalanceView(playerUuid));
        assertEquals(85L, api.getPlayerSpendableBalance(playerUuid));
        assertEquals(125L, api.getCustodialAvailable(playerUuid));
        assertTrue(api.playerHasEnough(playerUuid, 40L));
        assertEquals(IncomingPaymentDeliveryPreference.SKIP_INVENTORY, api.getIncomingPaymentDeliveryPreference(playerUuid));
        assertEquals(
                IncomingPaymentDeliveryPreference.SKIP_INVENTORY_AND_ENDERCHEST,
                api.setIncomingPaymentDeliveryPreference(
                        playerUuid,
                        "Alice",
                        IncomingPaymentDeliveryPreference.SKIP_INVENTORY_AND_ENDERCHEST
                )
        );

        verify(economyService).balanceView(argThat(hasUuid(playerUuid)));
        verify(economyService).getPlayerSpendableBalance(argThat(hasUuid(playerUuid)));
        verify(economyService).getCustodialAvailable(argThat(hasUuid(playerUuid)));
        verify(economyService).playerHasEnough(argThat(hasUuid(playerUuid)), eq(40L));
    }

    @Test
    void accountSharedAccountAndReservationOperationsDelegateToUnderlyingServices() {
        EconomyService economyService = mock(EconomyService.class);
        ReservationService reservationService = mock(ReservationService.class);
        EnderWalletService enderWalletService = mock(EnderWalletService.class);
        EnhancedLogger auditLog = mock(EnhancedLogger.class);
        EconomyPolApiProvider api = new EconomyPolApiProvider(
                economyService,
                reservationService,
                enderWalletService,
                denominationService,
                auditLog
        );
        UUID accountId = UUID.randomUUID();
        UUID ownerUuid = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        AccountRecord account = new AccountRecord(
                accountId,
                AccountType.SHARED,
                ownerUuid,
                "town-bank"
        );
        ReservationRecord reservation = new ReservationRecord(
                reservationId,
                accountId,
                500L,
                ReservationStatus.ACTIVE,
                "auction",
                123L
        );
        BalanceRecord balanceRecord = new BalanceRecord(500L, 75L);
        MoneyOperationResult deposit = MoneyOperationResult.success(200L, 200L, 0L, "deposited");
        MoneyOperationResult withdraw = MoneyOperationResult.success(150L, 150L, 0L, "withdrawn");

        when(economyService.ensurePlayerAccount(ownerUuid, "Alice")).thenReturn(account);
        when(economyService.findAccount(accountId)).thenReturn(Optional.of(account));
        when(economyService.findPlayerAccount(ownerUuid)).thenReturn(Optional.of(account));
        when(economyService.findAccountByName("town-bank")).thenReturn(Optional.of(account));
        when(economyService.getAccountName(accountId)).thenReturn(Optional.of("town-bank"));
        when(economyService.accountNameMap()).thenReturn(java.util.Map.of(accountId, "town-bank"));
        when(economyService.createSharedAccount(accountId, "town-bank", ownerUuid)).thenReturn(true);
        when(economyService.renameAccount(accountId, "nation-bank")).thenReturn(true);
        when(economyService.deleteSharedAccount(accountId)).thenReturn(true);
        when(economyService.isAccountOwner(accountId, ownerUuid)).thenReturn(true);
        when(economyService.setSharedAccountOwner(accountId, ownerUuid)).thenReturn(true);
        when(economyService.isAccountMember(accountId, ownerUuid)).thenReturn(true);
        when(economyService.addSharedAccountMember(accountId, ownerUuid)).thenReturn(true);
        when(economyService.removeSharedAccountMember(accountId, ownerUuid)).thenReturn(true);
        when(economyService.getSharedAccountBalance(accountId)).thenReturn(500L);
        when(economyService.sharedAccountHasEnough(accountId, 400L)).thenReturn(true);
        when(economyService.depositToSharedAccount(accountId, 200L, "PAYMENT")).thenReturn(deposit);
        when(economyService.withdrawFromSharedAccount(accountId, 150L, "PAYMENT")).thenReturn(withdraw);
        when(economyService.creditCustodial(ownerUuid, "Alice", 50L, "OVERFLOW")).thenReturn(balanceRecord);
        when(reservationService.reserve(accountId, 500L, "auction", 123L)).thenReturn(reservation);
        when(reservationService.release(reservationId)).thenReturn(true);
        when(reservationService.capture(reservationId)).thenReturn(true);

        api.registerPlayer(ownerUuid, "Alice");
        assertSame(account, api.ensurePlayerAccount(ownerUuid, "Alice"));
        assertEquals(Optional.of(account), api.findAccount(accountId));
        assertEquals(Optional.of(account), api.findPlayerAccount(ownerUuid));
        assertEquals(Optional.of(account), api.findAccountByName("town-bank"));
        assertEquals(Optional.of("town-bank"), api.getAccountName(accountId));
        assertEquals(java.util.Map.of(accountId, "town-bank"), api.getAccountNameMap());
        assertTrue(api.createSharedAccount(accountId, "town-bank", ownerUuid));
        assertTrue(api.renameAccount(accountId, "nation-bank"));
        assertTrue(api.deleteSharedAccount(accountId));
        assertTrue(api.isAccountOwner(accountId, ownerUuid));
        assertTrue(api.setSharedAccountOwner(accountId, ownerUuid));
        assertTrue(api.isAccountMember(accountId, ownerUuid));
        assertTrue(api.addSharedAccountMember(accountId, ownerUuid));
        assertTrue(api.removeSharedAccountMember(accountId, ownerUuid));
        assertEquals(500L, api.getSharedAccountBalance(accountId));
        assertTrue(api.sharedAccountHasEnough(accountId, 400L));
        assertSame(deposit, api.depositToSharedAccount(accountId, 200L, "PAYMENT"));
        assertSame(withdraw, api.withdrawFromSharedAccount(accountId, 150L, "PAYMENT"));
        assertSame(balanceRecord, api.creditCustodial(ownerUuid, "Alice", 50L, "OVERFLOW"));
        assertSame(reservation, api.reserve(accountId, 500L, "auction", 123L));
        assertTrue(api.releaseReservation(reservationId));
        assertTrue(api.captureReservation(reservationId));
    }

    @Test
    void managedEnderWalletAndLivePlayerOperationsDelegate() {
        EconomyService economyService = mock(EconomyService.class);
        ReservationService reservationService = mock(ReservationService.class);
        EnderWalletService enderWalletService = mock(EnderWalletService.class);
        EnhancedLogger auditLog = mock(EnhancedLogger.class);
        EconomyPolApiProvider api = new EconomyPolApiProvider(
                economyService,
                reservationService,
                enderWalletService,
                denominationService,
                auditLog
        );
        UUID playerUuid = UUID.randomUUID();
        Player player = mock(Player.class);
        EnderWalletSnapshot snapshot = new EnderWalletSnapshot(playerUuid, 250L, OfflineEnderWalletState.FROZEN, 999L);
        MoneyOperationResult liveDeposit = MoneyOperationResult.success(90L, 90L, 0L, "deposited");
        MoneyOperationResult liveWithdraw = MoneyOperationResult.success(60L, 60L, 0L, "withdrawn");
        MoneyOperationResult playerDeposit = MoneyOperationResult.success(100L, 100L, 0L, "credited");
        MoneyOperationResult playerWithdraw = MoneyOperationResult.success(80L, 80L, 0L, "debited");
        MoneyOperationResult walletDebit = MoneyOperationResult.success(120L, 100L, 20L, "debited");
        MoneyOperationResult walletCredit = MoneyOperationResult.success(80L, 80L, 0L, "credited");

        when(economyService.depositPhysicalMoneyToCustodial(player, 90L)).thenReturn(liveDeposit);
        when(economyService.withdrawCustodialAsPhysicalMoney(player, 60L, DEFAULT_ROUTING_ORDER)).thenReturn(liveWithdraw);
        when(economyService.getMaxWithdrawableCustodialToInventory(player)).thenReturn(126L);
        when(economyService.withdrawMaxCustodialToInventory(player)).thenReturn(liveWithdraw);
        when(economyService.depositToPlayerAccount(argThat(hasUuid(playerUuid)), eq(100L), eq("PAYMENT")))
                .thenReturn(playerDeposit);
        when(economyService.withdrawFromPlayerAccount(argThat(hasUuid(playerUuid)), eq(80L), eq("PAYMENT")))
                .thenReturn(playerWithdraw);
        when(economyService.isPlayerLocked(playerUuid)).thenReturn(true);
        when(enderWalletService.findSnapshot(playerUuid)).thenReturn(Optional.of(snapshot));
        when(enderWalletService.debitOffline(playerUuid, 120L)).thenReturn(walletDebit);
        when(enderWalletService.creditOffline(playerUuid, 80L)).thenReturn(walletCredit);

        assertSame(liveDeposit, api.depositPhysicalMoneyToCustodial(player, 90L));
        assertSame(liveWithdraw, api.withdrawCustodialAsPhysicalMoney(player, 60L, DEFAULT_ROUTING_ORDER));
        assertEquals(126L, api.getMaxWithdrawableCustodialToInventory(player));
        assertSame(liveWithdraw, api.withdrawMaxCustodialToInventory(player));
        assertSame(playerDeposit, api.depositToPlayerAccount(playerUuid, 100L, "PAYMENT"));
        assertSame(playerWithdraw, api.withdrawFromPlayerAccount(playerUuid, 80L, "PAYMENT"));
        assertTrue(api.isPlayerMoneyLocked(playerUuid));
        assertEquals(Optional.of(snapshot), api.getEnderWalletSnapshot(playerUuid));
        assertSame(walletDebit, api.debitOfflineEnderWallet(playerUuid, 120L));
        assertSame(walletCredit, api.creditOfflineEnderWallet(playerUuid, 80L));

        api.snapshotManagedEnderWallet(player);
        api.syncManagedEnderWallet(player);
        api.normalizeManagedEnderWallet(player);

        verify(enderWalletService).snapshotOnQuit(player);
        verify(enderWalletService).syncSnapshotOnJoin(player);
        verify(enderWalletService).normalizeOnlineEnderWallet(player);
    }

    @Test
    void denominationHelpersExposeCommodityUtilities() {
        EnhancedLogger auditLog = mock(EnhancedLogger.class);
        EconomyPolApiProvider api = new EconomyPolApiProvider(
                mock(EconomyService.class),
                mock(ReservationService.class),
                mock(EnderWalletService.class),
                denominationService,
                auditLog
        );

        List<Denomination> denominations = api.denominations();
        List<ItemStack> materialized = api.materialize(100L);
        long counted = api.countValue(List.of(
                new ItemStack(Material.GOLD_BLOCK, 1),
                new ItemStack(Material.GOLD_INGOT, 2),
                new ItemStack(Material.GOLD_NUGGET, 1)
        ));

        assertEquals(List.of(Material.GOLD_NUGGET, Material.GOLD_INGOT, Material.GOLD_BLOCK),
                denominations.stream().map(Denomination::material).toList());
        assertEquals(Optional.of(new Denomination(Material.GOLD_INGOT, 9L)), api.findDenomination(Material.GOLD_INGOT));
        assertTrue(api.isMoney(new ItemStack(Material.GOLD_BLOCK, 1)));
        assertEquals(18L, api.valueOf(new ItemStack(Material.GOLD_INGOT, 2)));
        assertEquals(100L, counted);
        assertEquals(3, materialized.size());
        assertEquals(Material.GOLD_BLOCK, materialized.get(0).getType());
        assertEquals(Material.GOLD_INGOT, materialized.get(1).getType());
        assertEquals(Material.GOLD_NUGGET, materialized.get(2).getType());
        assertEquals("2 Gold Coins", api.format(2L));
    }

    @Test
    void callerBoundApiLogsCallingPluginAndMethod() {
        EconomyService economyService = mock(EconomyService.class);
        ReservationService reservationService = mock(ReservationService.class);
        EnderWalletService enderWalletService = mock(EnderWalletService.class);
        EnhancedLogger auditLog = mock(EnhancedLogger.class);
        Plugin callerPlugin = MockBukkit.createMockPlugin();
        UUID playerUuid = UUID.randomUUID();
        PlayerBalanceView expectedView = new PlayerBalanceView(125L, 25L, 75L, 10L, false);
        EconomyPolApiProvider provider = new EconomyPolApiProvider(
                economyService,
                reservationService,
                enderWalletService,
                denominationService,
                auditLog
        );

        when(economyService.balanceView(argThat(hasUuid(playerUuid)))).thenReturn(expectedView);

        EconomyPolAPI api = provider.getInstance(callerPlugin);

        assertSame(expectedView, api.getPlayerBalanceView(playerUuid));
        verify(auditLog).info(contains("api-call caller=" + callerPlugin.getName()));
        verify(auditLog).info(contains("method=getPlayerBalanceView"));
    }

    private static ArgumentMatcher<org.bukkit.OfflinePlayer> hasUuid(UUID uuid) {
        return offlinePlayer -> offlinePlayer != null && uuid.equals(offlinePlayer.getUniqueId());
    }
}

