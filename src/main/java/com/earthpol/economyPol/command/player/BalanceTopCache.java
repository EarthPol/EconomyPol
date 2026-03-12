package com.earthpol.economyPol.command.player;

import com.earthpol.economyPol.command.shared.CommandDependencies;
import com.earthpol.economyPol.model.EnderWalletSnapshot;
import com.earthpol.economyPol.service.DenominationService;
import com.earthpol.economyPol.service.EconomyService;
import com.earthpol.economyPol.service.EnderWalletService;
import com.earthpol.economyPol.service.SchedulerService;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class BalanceTopCache {

    private static final Comparator<BalanceTopEntry> ENTRY_ORDER = Comparator
            .comparingLong(BalanceTopEntry::balance).reversed()
            .thenComparing(BalanceTopEntry::playerName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(BalanceTopEntry::playerUuid);

    private final EconomyService economyService;
    private final EnderWalletService enderWalletService;
    private final SchedulerService schedulerService;
    private final DenominationService denominationService;
    private final com.earthpol.earthPolLib.logging.EnhancedLogger operationsLog;
    private final int maxEntries;
    private final long ttlMillis;
    private final Object lock = new Object();
    private final Map<String, QueuedRequest> pendingRequests = new LinkedHashMap<>();

    private volatile CachedBalanceTop cachedSnapshot;
    private volatile boolean rebuildRunning;

    BalanceTopCache(CommandDependencies dependencies, int maxEntries) {
        this.economyService = dependencies.economyService();
        this.enderWalletService = dependencies.enderWalletService();
        this.schedulerService = dependencies.economyService().schedulerService();
        this.denominationService = dependencies.economyService().denominationService();
        this.operationsLog = dependencies.operationsLogger();
        this.maxEntries = maxEntries;
        this.ttlMillis = TimeUnit.SECONDS.toMillis(dependencies.settings().cache().balanceTopTtlSeconds());
    }

    void request(CommandSender sender) {
        CachedBalanceTop freshSnapshot = currentFreshSnapshot();
        if (freshSnapshot != null) {
            sendSnapshot(sender, freshSnapshot);
            return;
        }

        boolean shouldStartBuild = false;
        synchronized (lock) {
            freshSnapshot = currentFreshSnapshot();
            if (freshSnapshot != null) {
                sendSnapshot(sender, freshSnapshot);
                return;
            }
            pendingRequests.put(requestKey(sender), QueuedRequest.of(sender));
            if (!rebuildRunning) {
                rebuildRunning = true;
                shouldStartBuild = true;
            }
        }

        if (shouldStartBuild) {
            sender.sendMessage("Rebuilding the balancetop cache. You will receive the results when it completes.");
            if (!schedulerService.runAsync(this::rebuildCache, "balancetop-cache-rebuild")) {
                failQueuedRequests("Failed to schedule a balancetop cache rebuild.");
            }
        } else {
            sender.sendMessage("The balancetop cache is already rebuilding. You will receive the results when it completes.");
        }
    }

    private CachedBalanceTop currentFreshSnapshot() {
        CachedBalanceTop snapshot = cachedSnapshot;
        if (snapshot == null) {
            return null;
        }
        return snapshot.expiresAtMillis() > System.currentTimeMillis() ? snapshot : null;
    }

    private void rebuildCache() {
        try {
            CachedBalanceTop rebuilt = buildSnapshot();
            List<QueuedRequest> queuedRequests = completeRebuild(rebuilt);
            for (QueuedRequest queuedRequest : queuedRequests) {
                queuedRequest.resolve().ifPresent(sender -> sendSnapshotAsync(sender, rebuilt));
            }
        } catch (Exception exception) {
            operationsLog.severe("Failed to rebuild balancetop cache.", exception);
            failQueuedRequests("Failed to rebuild the balancetop cache. Check the server logs for details.");
        }
    }

    private List<QueuedRequest> completeRebuild(CachedBalanceTop rebuilt) {
        synchronized (lock) {
            cachedSnapshot = rebuilt;
            rebuildRunning = false;
            List<QueuedRequest> queuedRequests = new ArrayList<>(pendingRequests.values());
            pendingRequests.clear();
            return queuedRequests;
        }
    }

    private void failQueuedRequests(String message) {
        List<QueuedRequest> queuedRequests;
        synchronized (lock) {
            rebuildRunning = false;
            queuedRequests = new ArrayList<>(pendingRequests.values());
            pendingRequests.clear();
        }
        for (QueuedRequest queuedRequest : queuedRequests) {
            queuedRequest.resolve().ifPresent(sender ->
                    schedulerService.runOnCommandSenderContext(sender, () -> sender.sendMessage(message), "balancetop-cache-error")
            );
        }
    }

    private CachedBalanceTop buildSnapshot() {
        Map<UUID, String> knownNames = economyService.accountNameMap();
        Set<UUID> onlinePlayers = new HashSet<>();
        List<BalanceTopEntry> entries = new ArrayList<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            onlinePlayers.add(player.getUniqueId());
            long balance = economyService.scanOnlinePlayerMoney(player);
            if (balance <= 0L) {
                continue;
            }
            entries.add(new BalanceTopEntry(
                    player.getUniqueId(),
                    displayName(player.getUniqueId(), player.getName(), knownNames),
                    balance
            ));
        }

        int offlineSnapshotCount = 0;
        for (EnderWalletSnapshot snapshot : enderWalletService.listFrozenSnapshots()) {
            if (onlinePlayers.contains(snapshot.playerUuid())) {
                continue;
            }
            offlineSnapshotCount++;
            if (snapshot.baseUnits() <= 0L) {
                continue;
            }
            entries.add(new BalanceTopEntry(
                    snapshot.playerUuid(),
                    displayName(snapshot.playerUuid(), null, knownNames),
                    snapshot.baseUnits()
            ));
        }

        entries.sort(ENTRY_ORDER);
        if (entries.size() > maxEntries) {
            entries = new ArrayList<>(entries.subList(0, maxEntries));
        }

        long builtAtMillis = System.currentTimeMillis();
        return new CachedBalanceTop(
                List.copyOf(entries),
                builtAtMillis,
                builtAtMillis + ttlMillis,
                onlinePlayers.size(),
                offlineSnapshotCount
        );
    }

    private String displayName(UUID playerUuid, String liveName, Map<UUID, String> knownNames) {
        if (liveName != null && !liveName.isBlank()) {
            return liveName;
        }
        String storedName = knownNames.get(playerUuid);
        if (storedName != null && !storedName.isBlank()) {
            return storedName;
        }
        return playerUuid.toString();
    }

    private void sendSnapshot(CommandSender sender, CachedBalanceTop snapshot) {
        for (String line : formatLines(snapshot)) {
            sender.sendMessage(line);
        }
    }

    private void sendSnapshotAsync(CommandSender sender, CachedBalanceTop snapshot) {
        schedulerService.runOnCommandSenderContext(sender, () -> sendSnapshot(sender, snapshot), "balancetop-cache-delivery");
    }

    private List<String> formatLines(CachedBalanceTop snapshot) {
        List<String> lines = new ArrayList<>();
        long ageSeconds = Math.max(0L, TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - snapshot.builtAtMillis()));
        lines.add("Balance Top " + maxEntries + " (cache age: " + ageSeconds + "s)");
        if (snapshot.entries().isEmpty()) {
            lines.add("No player balances were found for the current balancetop scan.");
        } else {
            int rank = 1;
            for (BalanceTopEntry entry : snapshot.entries()) {
                lines.add(rank + ". " + entry.playerName() + " - " + denominationService.format(entry.balance()));
                rank++;
            }
        }
        lines.add("Scanned " + snapshot.scannedOnlinePlayers() + " online players and " +
                snapshot.scannedOfflineSnapshots() + " offline ender-wallet snapshots.");
        return lines;
    }

    private String requestKey(CommandSender sender) {
        if (sender instanceof Player player) {
            return "player:" + player.getUniqueId();
        }
        return sender.getClass().getName() + ":" + sender.getName();
    }

    private record BalanceTopEntry(UUID playerUuid, String playerName, long balance) {}

    private record CachedBalanceTop(
            List<BalanceTopEntry> entries,
            long builtAtMillis,
            long expiresAtMillis,
            int scannedOnlinePlayers,
            int scannedOfflineSnapshots
    ) {}

    private record QueuedRequest(UUID playerUuid, CommandSender nonPlayerSender) {

        static QueuedRequest of(CommandSender sender) {
            if (sender instanceof Player player) {
                return new QueuedRequest(player.getUniqueId(), null);
            }
            return new QueuedRequest(null, sender);
        }

        Optional<CommandSender> resolve() {
            if (playerUuid != null) {
                return Optional.ofNullable(Bukkit.getPlayer(playerUuid));
            }
            return Optional.ofNullable(nonPlayerSender);
        }
    }
}
