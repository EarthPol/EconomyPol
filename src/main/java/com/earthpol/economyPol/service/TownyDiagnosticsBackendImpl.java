package com.earthpol.economyPol.service;

import com.earthpol.earthPolLib.logging.EnhancedLogger;
import com.earthpol.economyPol.domain.AccountRecord;
import com.earthpol.economyPol.domain.DatabaseCheckFinding;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.object.Government;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Town;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class TownyDiagnosticsBackendImpl implements TownyDiagnosticsService.Backend {

    private static final int TOWNY_ACCOUNT_NAME_MAX_LENGTH = 32;

    private final EconomyService economyService;
    private final EnhancedLogger operationsLog;

    public TownyDiagnosticsBackendImpl(EconomyService economyService, EnhancedLogger operationsLog) {
        this.economyService = economyService;
        this.operationsLog = operationsLog;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public TownyAccountScanResult scanAccounts() {
        InspectionSnapshot snapshot = inspectAccounts();

        Map<String, Long> statistics = new LinkedHashMap<>();
        statistics.put("towny_town_accounts_in_db", snapshot.dbTownAccounts());
        statistics.put("towny_nation_accounts_in_db", snapshot.dbNationAccounts());
        statistics.put("towns_in_towny", (long) snapshot.townsByAccountId().size());
        statistics.put("nations_in_towny", (long) snapshot.nationsByAccountId().size());
        statistics.put("orphan_town_accounts", snapshot.orphanTownAccounts());
        statistics.put("orphan_nation_accounts", snapshot.orphanNationAccounts());
        statistics.put("uuid_mismatch_accounts", snapshot.uuidMismatchAccounts());
        statistics.put("owner_mismatch_accounts", snapshot.ownerMismatchAccounts());
        statistics.put("missing_town_accounts", snapshot.missingTownAccounts());
        statistics.put("missing_nation_accounts", snapshot.missingNationAccounts());

        List<DatabaseCheckFinding> findings = new ArrayList<>();
        findings.addAll(snapshot.findings());

        String summary = findings.isEmpty()
                ? "No Towny shared-account inconsistencies were found."
                : "Found " + findings.size() + " Towny shared-account inconsistencies.";

        List<String> notes = List.of(
                "Towny bank accounts are checked against Towny's current town/nation set using the modified NPC UUID path.",
                "Cleanup only removes orphaned Towny rows. UUID or owner mismatches are reported but not auto-rewritten."
        );

        return new TownyAccountScanResult(true, summary, statistics, notes, List.copyOf(findings));
    }

    @Override
    public TownyCleanupResult cleanupOrphanedAccounts() {
        Instant ranAt = Instant.now();
        long startedNanos = System.nanoTime();
        InspectionSnapshot snapshot = inspectAccounts();

        List<String> deletedEntries = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        long deletedTownAccounts = 0L;
        long deletedNationAccounts = 0L;

        for (TownyAccountInspection inspection : snapshot.inspections()) {
            if (!inspection.orphan()) {
                continue;
            }
            boolean deleted = economyService.deleteSharedAccount(inspection.account().accountId());
            if (deleted) {
                String deletedEntry = inspection.type() + ":" + inspection.account().accountName() +
                        " account_id=" + inspection.account().accountId();
                deletedEntries.add(deletedEntry);
                if ("TOWN".equals(inspection.type())) {
                    deletedTownAccounts++;
                } else {
                    deletedNationAccounts++;
                }
            } else {
                notes.add("Failed to delete orphaned " + inspection.type().toLowerCase(Locale.ROOT) +
                        " account " + inspection.account().accountName() + " (" + inspection.account().accountId() + ").");
            }
        }

        Map<String, Long> statistics = new LinkedHashMap<>();
        statistics.put("orphan_town_accounts_deleted", deletedTownAccounts);
        statistics.put("orphan_nation_accounts_deleted", deletedNationAccounts);
        statistics.put("orphan_accounts_deleted_total", deletedTownAccounts + deletedNationAccounts);
        statistics.put("orphan_accounts_remaining", snapshot.orphanTownAccounts() + snapshot.orphanNationAccounts()
                - deletedTownAccounts - deletedNationAccounts);

        long durationMillis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        String summary = deletedEntries.isEmpty()
                ? "No orphaned Towny shared accounts were deleted."
                : "Deleted " + deletedEntries.size() + " orphaned Towny shared account(s).";
        return new TownyCleanupResult(true, ranAt, durationMillis, summary, statistics, List.copyOf(notes), List.copyOf(deletedEntries));
    }

    private InspectionSnapshot inspectAccounts() {
        List<AccountRecord> sharedAccounts = economyService.listSharedAccounts();
        Map<UUID, Town> townsByAccountId = TownyAPI.getInstance().getTowns().stream()
                .collect(Collectors.toMap(town -> TownyEconomyHandler.modifyNPCUUID(town.getUUID()), town -> town));
        Map<UUID, Nation> nationsByAccountId = TownyAPI.getInstance().getNations().stream()
                .collect(Collectors.toMap(nation -> TownyEconomyHandler.modifyNPCUUID(nation.getUUID()), nation -> nation));
        Map<String, Town> townsByAccountName = TownyAPI.getInstance().getTowns().stream()
                .collect(Collectors.toMap(town -> normalize(bankAccountName(TownySettings.getTownAccountPrefix(), town.getName())), town -> town));
        Map<String, Nation> nationsByAccountName = TownyAPI.getInstance().getNations().stream()
                .collect(Collectors.toMap(nation -> normalize(bankAccountName(TownySettings.getNationAccountPrefix(), nation.getName())), nation -> nation));

        Set<UUID> sharedAccountIds = sharedAccounts.stream().map(AccountRecord::accountId).collect(Collectors.toSet());

        List<DatabaseCheckFinding> findings = new ArrayList<>();
        List<TownyAccountInspection> inspections = new ArrayList<>();
        long dbTownAccounts = 0L;
        long dbNationAccounts = 0L;
        long orphanTownAccounts = 0L;
        long orphanNationAccounts = 0L;
        long uuidMismatchAccounts = 0L;
        long ownerMismatchAccounts = 0L;

        String townPrefix = TownySettings.getTownAccountPrefix();
        String nationPrefix = TownySettings.getNationAccountPrefix();

        for (AccountRecord account : sharedAccounts) {
            Town townById = townsByAccountId.get(account.accountId());
            Nation nationById = nationsByAccountId.get(account.accountId());
            Town townByName = townsByAccountName.get(normalize(account.accountName()));
            Nation nationByName = nationsByAccountName.get(normalize(account.accountName()));

            if (townById != null || townByName != null || account.accountName().startsWith(townPrefix)) {
                dbTownAccounts++;
                Town town = townById != null ? townById : townByName;
                if (town == null) {
                    orphanTownAccounts++;
                    findings.add(finding(account, "economy_accounts", "Orphaned Towny town account; no Towny town currently matches this row."));
                    inspections.add(new TownyAccountInspection("TOWN", account, true));
                    continue;
                }
                UUID expectedAccountId = TownyEconomyHandler.modifyNPCUUID(town.getUUID());
                String expectedName = bankAccountName(townPrefix, town.getName());
                if (!account.accountId().equals(expectedAccountId)) {
                    uuidMismatchAccounts++;
                    findings.add(finding(account, "economy_accounts",
                            "Towny town account UUID mismatch; expected account_id=" + expectedAccountId));
                }
                if (!expectedAccountId.equals(account.ownerUuid())) {
                    ownerMismatchAccounts++;
                    findings.add(finding(account, "economy_accounts",
                            "Towny town account owner_uuid mismatch; expected owner_uuid=" + expectedAccountId));
                }
                if (!expectedName.equals(account.accountName())) {
                    findings.add(finding(account, "economy_accounts",
                            "Towny town account name mismatch; expected account_name=" + expectedName));
                }
                inspections.add(new TownyAccountInspection("TOWN", account, false));
                continue;
            }

            if (nationById != null || nationByName != null || account.accountName().startsWith(nationPrefix)) {
                dbNationAccounts++;
                Nation nation = nationById != null ? nationById : nationByName;
                if (nation == null) {
                    orphanNationAccounts++;
                    findings.add(finding(account, "economy_accounts", "Orphaned Towny nation account; no Towny nation currently matches this row."));
                    inspections.add(new TownyAccountInspection("NATION", account, true));
                    continue;
                }
                UUID expectedAccountId = TownyEconomyHandler.modifyNPCUUID(nation.getUUID());
                String expectedName = bankAccountName(nationPrefix, nation.getName());
                if (!account.accountId().equals(expectedAccountId)) {
                    uuidMismatchAccounts++;
                    findings.add(finding(account, "economy_accounts",
                            "Towny nation account UUID mismatch; expected account_id=" + expectedAccountId));
                }
                if (!expectedAccountId.equals(account.ownerUuid())) {
                    ownerMismatchAccounts++;
                    findings.add(finding(account, "economy_accounts",
                            "Towny nation account owner_uuid mismatch; expected owner_uuid=" + expectedAccountId));
                }
                if (!expectedName.equals(account.accountName())) {
                    findings.add(finding(account, "economy_accounts",
                            "Towny nation account name mismatch; expected account_name=" + expectedName));
                }
                inspections.add(new TownyAccountInspection("NATION", account, false));
            }
        }

        long missingTownAccounts = 0L;
        for (Town town : TownyAPI.getInstance().getTowns()) {
            UUID expectedAccountId = TownyEconomyHandler.modifyNPCUUID(town.getUUID());
            if (sharedAccountIds.contains(expectedAccountId)) {
                continue;
            }
            missingTownAccounts++;
            findings.add(new DatabaseCheckFinding(
                    "economy_accounts",
                    "town=" + town.getName() + ", expected_account_id=" + expectedAccountId,
                    "Towny town is missing its canonical shared account row"
            ));
        }

        long missingNationAccounts = 0L;
        for (Nation nation : TownyAPI.getInstance().getNations()) {
            UUID expectedAccountId = TownyEconomyHandler.modifyNPCUUID(nation.getUUID());
            if (sharedAccountIds.contains(expectedAccountId)) {
                continue;
            }
            missingNationAccounts++;
            findings.add(new DatabaseCheckFinding(
                    "economy_accounts",
                    "nation=" + nation.getName() + ", expected_account_id=" + expectedAccountId,
                    "Towny nation is missing its canonical shared account row"
            ));
        }

        return new InspectionSnapshot(
                townsByAccountId,
                nationsByAccountId,
                dbTownAccounts,
                dbNationAccounts,
                orphanTownAccounts,
                orphanNationAccounts,
                uuidMismatchAccounts,
                ownerMismatchAccounts,
                missingTownAccounts,
                missingNationAccounts,
                List.copyOf(findings),
                List.copyOf(inspections)
        );
    }

    private DatabaseCheckFinding finding(AccountRecord account, String table, String detail) {
        return new DatabaseCheckFinding(
                table,
                "account_id=" + account.accountId() + ", account_name=" + account.accountName(),
                detail
        );
    }

    private String bankAccountName(String prefix, String governmentName) {
        String fullName = prefix + governmentName;
        return fullName.length() <= TOWNY_ACCOUNT_NAME_MAX_LENGTH
                ? fullName
                : fullName.substring(0, TOWNY_ACCOUNT_NAME_MAX_LENGTH);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record TownyAccountInspection(String type, AccountRecord account, boolean orphan) {
    }

    private record InspectionSnapshot(
            Map<UUID, Town> townsByAccountId,
            Map<UUID, Nation> nationsByAccountId,
            long dbTownAccounts,
            long dbNationAccounts,
            long orphanTownAccounts,
            long orphanNationAccounts,
            long uuidMismatchAccounts,
            long ownerMismatchAccounts,
            long missingTownAccounts,
            long missingNationAccounts,
            List<DatabaseCheckFinding> findings,
            List<TownyAccountInspection> inspections
    ) {
    }
}
