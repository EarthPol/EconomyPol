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
import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.logging.EconomyLoggers.LogType;
import com.earthpol.economyPol.economy.service.support.DenominationService;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.economy.service.player.EnderWalletService;
import com.earthpol.economyPol.economy.service.support.ReservationService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class EconomyPolApiProvider implements EconomyPolAPI, EconomyPolApiFactory {

    private final EconomyService economyService;
    private final ReservationService reservationService;
    private final EnderWalletService enderWalletService;
    private final DenominationService denominationService;
    private final EconomyLoggers loggers;

    public EconomyPolApiProvider(
            EconomyService economyService,
            ReservationService reservationService,
            EnderWalletService enderWalletService,
            DenominationService denominationService,
            EconomyLoggers loggers
    ) {
        this.economyService = economyService;
        this.reservationService = reservationService;
        this.enderWalletService = enderWalletService;
        this.denominationService = denominationService;
        this.loggers = loggers;
    }

    @Override
    public EconomyPolAPI getInstance(Plugin callerPlugin) {
        if (callerPlugin == null) {
            throw new IllegalArgumentException("callerPlugin cannot be null.");
        }
        return (EconomyPolAPI) Proxy.newProxyInstance(
                EconomyPolAPI.class.getClassLoader(),
                new Class<?>[] {EconomyPolAPI.class},
                (proxy, method, args) -> invokeCallerAware(callerPlugin, method, args, proxy)
        );
    }

    @Override
    public AccountRecord ensurePlayerAccount(UUID playerUuid, String playerName) {
        return economyService.ensurePlayerAccount(playerUuid, playerName);
    }

    @Override
    public void registerPlayer(UUID playerUuid, String playerName) {
        economyService.registerPlayer(playerUuid, playerName);
    }

    @Override
    public Optional<AccountRecord> findAccount(UUID accountId) {
        return economyService.findAccount(accountId);
    }

    @Override
    public Optional<AccountRecord> findPlayerAccount(UUID playerUuid) {
        return economyService.findPlayerAccount(playerUuid);
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
    public long getPlayerSpendableBalance(UUID playerUuid) {
        return economyService.getPlayerSpendableBalance(Bukkit.getOfflinePlayer(playerUuid));
    }

    @Override
    public long getCustodialAvailable(UUID playerUuid) {
        return economyService.getCustodialAvailable(Bukkit.getOfflinePlayer(playerUuid));
    }

    @Override
    public boolean playerHasEnough(UUID playerUuid, long amount) {
        return economyService.playerHasEnough(Bukkit.getOfflinePlayer(playerUuid), amount);
    }

    @Override
    public MoneyOperationResult depositToPlayerAccount(UUID playerUuid, long amount, String reason) {
        return economyService.depositToPlayerAccount(Bukkit.getOfflinePlayer(playerUuid), amount, reason);
    }

    @Override
    public MoneyOperationResult withdrawFromPlayerAccount(UUID playerUuid, long amount, String reason) {
        return economyService.withdrawFromPlayerAccount(Bukkit.getOfflinePlayer(playerUuid), amount, reason);
    }

    @Override
    public MoneyOperationResult depositPhysicalMoneyToCustodial(Player player, long amount) {
        return economyService.depositPhysicalMoneyToCustodial(player, amount);
    }

    @Override
    public MoneyOperationResult withdrawCustodialAsPhysicalMoney(
            Player player,
            long amount,
            List<MoneyRouteTarget> routingOrder
    ) {
        return economyService.withdrawCustodialAsPhysicalMoney(player, amount, routingOrder);
    }

    @Override
    public long getMaxWithdrawableCustodialToInventory(Player player) {
        return economyService.getMaxWithdrawableCustodialToInventory(player);
    }

    @Override
    public MoneyOperationResult withdrawMaxCustodialToInventory(Player player) {
        return economyService.withdrawMaxCustodialToInventory(player);
    }

    @Override
    public BalanceRecord creditCustodial(UUID playerUuid, String playerName, long amount, String reason) {
        return economyService.creditCustodial(playerUuid, playerName, amount, reason);
    }

    @Override
    public IncomingPaymentDeliveryPreference getIncomingPaymentDeliveryPreference(UUID playerUuid) {
        return economyService.getIncomingPaymentDeliveryPreference(Bukkit.getOfflinePlayer(playerUuid));
    }

    @Override
    public IncomingPaymentDeliveryPreference setIncomingPaymentDeliveryPreference(
            UUID playerUuid,
            String playerName,
            IncomingPaymentDeliveryPreference preference
    ) {
        return economyService.setIncomingPaymentDeliveryPreference(playerUuid, playerName, preference);
    }

    @Override
    public long getSharedAccountBalance(UUID accountId) {
        return economyService.getSharedAccountBalance(accountId);
    }

    @Override
    public boolean sharedAccountHasEnough(UUID accountId, long amount) {
        return economyService.sharedAccountHasEnough(accountId, amount);
    }

    @Override
    public MoneyOperationResult depositToSharedAccount(UUID accountId, long amount, String reason) {
        return economyService.depositToSharedAccount(accountId, amount, reason);
    }

    @Override
    public MoneyOperationResult withdrawFromSharedAccount(UUID accountId, long amount, String reason) {
        return economyService.withdrawFromSharedAccount(accountId, amount, reason);
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

    private Object invokeCallerAware(Plugin callerPlugin, Method method, Object[] args, Object proxy) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return handleObjectMethod(callerPlugin, method, args, proxy);
        }

        try {
            Object result = method.invoke(this, args);
            loggers.log("api-call caller=" + callerPlugin.getName() +
                    " method=" + method.getName() +
                    " args=" + summarizeArgs(args) +
                    " result=" + summarize(result), LogType.AUDIT);
            return result;
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            loggers.logSevere("api-call-failed caller=" + callerPlugin.getName() +
                    " method=" + method.getName() +
                    " args=" + summarizeArgs(args), LogType.AUDIT, cause);
            throw cause;
        }
    }

    private Object handleObjectMethod(Plugin callerPlugin, Method method, Object[] args, Object proxy) {
        return switch (method.getName()) {
            case "toString" -> "EconomyPolAPI[caller=" + callerPlugin.getName() + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
            default -> throw new IllegalStateException("Unexpected Object method: " + method.getName());
        };
    }

    private String summarizeArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        return Arrays.stream(args)
                .map(this::summarize)
                .toList()
                .toString();
    }

    private String summarize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Player player) {
            return "Player(name=" + player.getName() + ",uuid=" + player.getUniqueId() + ")";
        }
        if (value instanceof Plugin plugin) {
            return "Plugin(name=" + plugin.getName() + ")";
        }
        if (value instanceof MoneyOperationResult result) {
            return "MoneyOperationResult(success=" + result.success() +
                    ",processed=" + result.processedAmount() +
                    ",remainder=" + result.remainder() +
                    ",failureReason=" + result.failureReason() + ")";
        }
        if (value instanceof AccountRecord accountRecord) {
            return "AccountRecord(id=" + accountRecord.accountId() +
                    ",type=" + accountRecord.accountType() +
                    ",name=" + accountRecord.accountName() + ")";
        }
        if (value instanceof BalanceRecord balanceRecord) {
            return "BalanceRecord(available=" + balanceRecord.availableBalance() +
                    ",reserved=" + balanceRecord.reservedBalance() + ")";
        }
        if (value instanceof Optional<?> optional) {
            return optional.map(this::summarize).orElse("Optional.empty");
        }
        if (value instanceof List<?> list) {
            List<String> preview = list.stream()
                    .limit(3)
                    .map(this::summarize)
                    .toList();
            return "List(size=" + list.size() + ",preview=" + preview + ")";
        }
        if (value instanceof Map<?, ?> map) {
            return "Map(size=" + map.size() + ")";
        }
        if (value instanceof ItemStack itemStack) {
            return "ItemStack(type=" + itemStack.getType() + ",amount=" + itemStack.getAmount() + ")";
        }
        return String.valueOf(value);
    }
}
