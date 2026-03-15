package com.earthpol.economyPol.economy.api;

import com.earthpol.economyPol.economy.model.BalanceRecord;
import com.earthpol.economyPol.economy.model.IncomingPaymentDeliveryPreference;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import com.earthpol.economyPol.economy.model.MoneyRouteTarget;
import com.earthpol.economyPol.economy.model.PlayerBalanceView;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public interface EconomyPolPlayerAPI {

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
}
