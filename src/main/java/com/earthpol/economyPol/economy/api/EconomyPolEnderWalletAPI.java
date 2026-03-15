package com.earthpol.economyPol.economy.api;

import com.earthpol.economyPol.economy.model.EnderWalletSnapshot;
import com.earthpol.economyPol.economy.model.MoneyOperationResult;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

public interface EconomyPolEnderWalletAPI {

    Optional<EnderWalletSnapshot> getEnderWalletSnapshot(UUID playerUuid);

    MoneyOperationResult debitOfflineEnderWallet(UUID playerUuid, long amount);

    MoneyOperationResult creditOfflineEnderWallet(UUID playerUuid, long amount);

    void snapshotManagedEnderWallet(Player player);

    void syncManagedEnderWallet(Player player);

    void normalizeManagedEnderWallet(Player player);
}
