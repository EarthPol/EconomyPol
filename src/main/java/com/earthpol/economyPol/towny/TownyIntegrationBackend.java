package com.earthpol.economyPol.towny;

import com.earthpol.economyPol.economy.logging.EconomyLoggers;
import com.earthpol.economyPol.economy.logging.EconomyLoggers.LogType;
import com.earthpol.economyPol.economy.model.AccountRecord;
import com.earthpol.economyPol.economy.model.DatabaseCheckFinding;
import com.earthpol.economyPol.economy.service.EconomyService;
import com.earthpol.economyPol.towny.model.TownyAccountScanResult;
import com.earthpol.economyPol.towny.model.TownyCleanupResult;
import com.earthpol.economyPol.towny.model.TownyGovernmentBinding;
import com.earthpol.economyPol.towny.model.TownyGovernmentType;
import com.earthpol.economyPol.towny.repository.TownyGovernmentRepository;
import com.palmergames.bukkit.towny.TownyAPI;
import com.palmergames.bukkit.towny.TownyEconomyHandler;
import com.palmergames.bukkit.towny.object.Government;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Town;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.Locale;

public final class TownyIntegrationBackend implements TownyService.Backend {

    private static final int TOWNY_ACCOUNT_NAME_MAX_LENGTH = 32;

    private final EconomyService economyService;
    private final TownyGovernmentRepository townyGovernmentRepository;
    private final EconomyLoggers loggers;

    public TownyIntegrationBackend(
            EconomyService economyService,
            TownyGovernmentRepository townyGovernmentRepository,
            EconomyLoggers loggers
    ) {
        this.economyService = economyService;
        this.townyGovernmentRepository = townyGovernmentRepository;
        this.loggers = loggers;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void synchronizeAllGovernments() {
        int synchronizedCount = 0;
        for (Town town : TownyAPI.getInstance().getTowns()) {
            if (syncGovernment(town)) {
                synchronizedCount++;
            }
        }
        for (Nation nation : TownyAPI.getInstance().getNations()) {
            if (syncGovernment(nation)) {
                synchronizedCount++;
            }
        }
        loggers.log("Towny synchronization completed. synchronized_governments=" + synchronizedCount, LogType.OPERATIONS);
    }

    @Override
    public void refreshTown(UUID townUuid) {
        Town town = TownyAPI.getInstance().getTown(townUuid);
        if (town == null) {
            loggers.logWarn("Towny refresh requested for missing town uuid=" + townUuid, LogType.OPERATIONS);
            return;
        }
        syncGovernment(town);
    }

    @Override
    public void refreshNation(UUID nationUuid) {
        Nation nation = TownyAPI.getInstance().getNation(nationUuid);
        if (nation == null) {
            loggers.logWarn("Towny refresh requested for missing nation uuid=" + nationUuid, LogType.OPERATIONS);
            return;
        }
        syncGovernment(nation);
    }

    @Override
    public void deleteTown(UUID townUuid, String townName) {
        deleteGovernment(TownyGovernmentType.TOWN, townUuid, townName);
    }

    @Override
    public void deleteNation(UUID nationUuid, String nationName) {
        deleteGovernment(TownyGovernmentType.NATION, nationUuid, nationName);
    }

    @Override
    public TownyAccountScanResult scanAccounts() {
        InspectionSnapshot snapshot = inspectAccounts();
        Map<String, Long> statistics = new LinkedHashMap<>();
        statistics.put("binding_rows", (long) snapshot.bindings().size());
        statistics.put("town_bindings", snapshot.townBindings());
        statistics.put("nation_bindings", snapshot.nationBindings());
        statistics.put("towns_in_towny", (long) snapshot.currentTownCount());
        statistics.put("nations_in_towny", (long) snapshot.currentNationCount());
        statistics.put("orphan_bindings", snapshot.orphanBindings());
        statistics.put("missing_bindings", snapshot.missingBindings());
        statistics.put("binding_row_mismatches", snapshot.bindingMismatches());
        statistics.put("legacy_unbound_rows", snapshot.legacyUnboundRows());
        statistics.put("legacy_conflicting_rows", snapshot.legacyConflictingRows());

        String summary = snapshot.findings().isEmpty()
                ? "No Towny account binding inconsistencies were found."
                : "Found " + snapshot.findings().size() + " Towny account binding inconsistencies.";

        List<String> notes = List.of(
                "Towny governments are tracked explicitly in economy_towny_governments using the raw government UUID and the current Towny bank account UUID.",
                "Cleanup only removes orphaned bindings and legacy unbound Towny-style shared-account rows. UUID or owner mismatches are reported but not auto-rewritten."
        );

        return new TownyAccountScanResult(true, summary, statistics, notes, snapshot.findings());
    }

    @Override
    public TownyCleanupResult cleanupOrphanedAccounts() {
        Instant ranAt = Instant.now();
        long startedNanos = System.nanoTime();
        InspectionSnapshot snapshot = inspectAccounts();

        List<String> deletedEntries = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        long deletedBindings = 0L;
        long deletedLegacyRows = 0L;
        Set<UUID> processedAccountIds = new LinkedHashSet<>();

        for (CleanupCandidate candidate : snapshot.cleanupCandidates()) {
            if (!processedAccountIds.add(candidate.accountId())) {
                continue;
            }
            boolean deleted = economyService.deleteSharedAccount(candidate.accountId());
            if (deleted) {
                deletedEntries.add(candidate.type() + ":" + candidate.accountName() + " account_id=" + candidate.accountId());
                if ("BOUND_ORPHAN".equals(candidate.type())) {
                    deletedBindings++;
                } else {
                    deletedLegacyRows++;
                }
            } else {
                notes.add("Failed to delete Towny cleanup candidate account_id=" + candidate.accountId() +
                        " account_name=" + candidate.accountName() + " reason=" + candidate.reason());
            }
        }

        Map<String, Long> statistics = new LinkedHashMap<>();
        statistics.put("orphan_bindings_deleted", deletedBindings);
        statistics.put("legacy_unbound_rows_deleted", deletedLegacyRows);
        statistics.put("deleted_total", deletedBindings + deletedLegacyRows);
        statistics.put("cleanup_candidates_remaining",
                Math.max(0L, snapshot.cleanupCandidates().stream().map(CleanupCandidate::accountId).distinct().count() - deletedBindings - deletedLegacyRows));

        long durationMillis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        String summary = deletedEntries.isEmpty()
                ? "No orphaned Towny rows were deleted."
                : "Deleted " + deletedEntries.size() + " orphaned Towny row(s).";
        return new TownyCleanupResult(true, ranAt, durationMillis, summary, statistics, List.copyOf(notes), List.copyOf(deletedEntries));
    }

    private boolean syncGovernment(Government government) {
        return syncGovernment(
                TownyGovernmentType.fromGovernment(government),
                government.getUUID(),
                government.getName(),
                government.getAccount().getUUID(),
                government.getAccount().getName()
        );
    }

    private boolean syncGovernment(
            TownyGovernmentType governmentType,
            UUID governmentUuid,
            String governmentName,
            UUID bankAccountUuid,
            String bankAccountName
    ) {
        Optional<TownyGovernmentBinding> existingBinding = townyGovernmentRepository.findByGovernment(governmentType, governmentUuid);
        if (existingBinding.isPresent() && !existingBinding.get().accountId().equals(bankAccountUuid)) {
            loggers.logSevere("Towny binding UUID mismatch detected during sync. government_type=" + governmentType +
                    " government_uuid=" + governmentUuid +
                    " bound_account_id=" + existingBinding.get().accountId() +
                    " current_bank_account_uuid=" + bankAccountUuid +
                    " account_name=" + bankAccountName, LogType.OPERATIONS);
            return false;
        }

        Optional<AccountRecord> conflictingByName = economyService.findSharedAccount(bankAccountName);
        if (conflictingByName.isPresent() && !conflictingByName.get().accountId().equals(bankAccountUuid)) {
            Optional<TownyGovernmentBinding> conflictingBinding = townyGovernmentRepository.findByAccountId(conflictingByName.get().accountId());
            loggers.logWarn("Towny sync found a conflicting shared account name. government_type=" + governmentType +
                    " government_uuid=" + governmentUuid +
                    " expected_account_id=" + bankAccountUuid +
                    " conflicting_account_id=" + conflictingByName.get().accountId() +
                    " account_name=" + bankAccountName +
                    " conflicting_bound=" + conflictingBinding.isPresent() +
                    ". Run '/economypol admin check towny-accounts' and '/economypol admin cleanup towny-orphans' if this row is stale.",
                    LogType.OPERATIONS);
            return false;
        }

        boolean created = economyService.createSharedAccount(bankAccountUuid, bankAccountName, bankAccountUuid);
        if (!created) {
            loggers.logWarn("Towny sync could not create or validate the shared account. government_type=" + governmentType +
                    " government_uuid=" + governmentUuid +
                    " bank_account_uuid=" + bankAccountUuid +
                    " bank_account_name=" + bankAccountName, LogType.OPERATIONS);
            return false;
        }

        economyService.renameAccount(bankAccountUuid, bankAccountName);
        economyService.setSharedAccountOwner(bankAccountUuid, bankAccountUuid);
        townyGovernmentRepository.upsertBinding(
                bankAccountUuid,
                governmentType,
                governmentUuid,
                bankAccountUuid,
                governmentName,
                bankAccountName
        );
        loggers.log("towny-sync government_type=" + governmentType +
                " government_uuid=" + governmentUuid +
                " bank_account_uuid=" + bankAccountUuid +
                " bank_account_name=" + bankAccountName, LogType.OPERATIONS);
        return true;
    }

    private void deleteGovernment(TownyGovernmentType governmentType, UUID governmentUuid, String governmentName) {
        Optional<TownyGovernmentBinding> binding = townyGovernmentRepository.findByGovernment(governmentType, governmentUuid);
        if (binding.isPresent() && economyService.deleteSharedAccount(binding.get().accountId())) {
            loggers.log("towny-delete-cleanup government_type=" + governmentType +
                    " government_uuid=" + governmentUuid +
                    " bank_account_uuid=" + binding.get().accountId() +
                    " government_name=" + governmentName, LogType.OPERATIONS);
            return;
        }

        UUID fallbackBankUuid = TownyEconomyHandler.modifyNPCUUID(governmentUuid);
        if (economyService.deleteSharedAccount(fallbackBankUuid)) {
            loggers.logWarn("towny-delete-cleanup-fallback government_type=" + governmentType +
                    " government_uuid=" + governmentUuid +
                    " bank_account_uuid=" + fallbackBankUuid +
                    " government_name=" + governmentName, LogType.OPERATIONS);
            return;
        }

        String expectedBankName = trimmedBankAccountName(governmentType, governmentName);
        Optional<AccountRecord> fallbackByName = economyService.findSharedAccount(expectedBankName);
        if (fallbackByName.isPresent() && economyService.deleteSharedAccount(fallbackByName.get().accountId())) {
            loggers.logWarn("towny-delete-cleanup-name-fallback government_type=" + governmentType +
                    " government_uuid=" + governmentUuid +
                    " deleted_account_id=" + fallbackByName.get().accountId() +
                    " bank_account_name=" + expectedBankName, LogType.OPERATIONS);
            return;
        }

        // Towny removes government accounts through the economy provider before it fires the delete event.
        // If nothing is left here, cleanup already happened and this is a normal no-op.
        loggers.log("towny-delete-successfully-completed government_type=" + governmentType +
                " government_uuid=" + governmentUuid +
                " government_name=" + governmentName +
                " expected_bank_account_uuid=" + fallbackBankUuid +
                " expected_bank_account_name=" + expectedBankName +
                " reason=account_already_removed", LogType.OPERATIONS);
    }

    private InspectionSnapshot inspectAccounts() {
        List<TownyGovernmentBinding> bindings = townyGovernmentRepository.listBindings();
        List<AccountRecord> sharedAccounts = economyService.listSharedAccounts();
        Map<UUID, AccountRecord> sharedAccountsById = sharedAccounts.stream()
                .collect(Collectors.toMap(AccountRecord::accountId, account -> account));
        Map<UUID, TownyGovernmentBinding> bindingsByAccountId = bindings.stream()
                .collect(Collectors.toMap(TownyGovernmentBinding::accountId, binding -> binding));
        Map<GovernmentKey, TownyGovernmentBinding> bindingsByGovernment = bindings.stream()
                .collect(Collectors.toMap(
                        binding -> new GovernmentKey(binding.governmentType(), binding.governmentUuid()),
                        binding -> binding
                ));

        Map<GovernmentKey, GovernmentSnapshot> currentGovernments = currentGovernments();
        Map<UUID, GovernmentSnapshot> currentByBankAccountId = currentGovernments.values().stream()
                .collect(Collectors.toMap(GovernmentSnapshot::bankAccountUuid, snapshot -> snapshot));
        Map<String, GovernmentSnapshot> currentByBankAccountName = currentGovernments.values().stream()
                .collect(Collectors.toMap(
                        snapshot -> normalize(snapshot.bankAccountName()),
                        snapshot -> snapshot,
                        (left, right) -> left
                ));

        List<DatabaseCheckFinding> findings = new ArrayList<>();
        List<CleanupCandidate> cleanupCandidates = new ArrayList<>();
        long townBindings = 0L;
        long nationBindings = 0L;
        long orphanBindings = 0L;
        long missingBindings = 0L;
        long bindingMismatches = 0L;
        long legacyUnboundRows = 0L;
        long legacyConflictingRows = 0L;

        for (TownyGovernmentBinding binding : bindings) {
            if (binding.governmentType() == TownyGovernmentType.TOWN) {
                townBindings++;
            } else {
                nationBindings++;
            }

            GovernmentSnapshot current = currentGovernments.get(new GovernmentKey(binding.governmentType(), binding.governmentUuid()));
            if (current == null) {
                orphanBindings++;
                findings.add(new DatabaseCheckFinding(
                        "economy_towny_governments",
                        "government_uuid=" + binding.governmentUuid() + ", account_id=" + binding.accountId(),
                        "Binding points to a Towny " + binding.governmentType().displayNameLower() +
                                " that no longer exists."
                ));
                cleanupCandidates.add(new CleanupCandidate(
                        "BOUND_ORPHAN",
                        binding.accountId(),
                        binding.bankAccountName(),
                        "binding has no matching current Towny government"
                ));
                continue;
            }

            if (!binding.bankAccountUuid().equals(current.bankAccountUuid())) {
                bindingMismatches++;
                findings.add(new DatabaseCheckFinding(
                        "economy_towny_governments",
                        "government_uuid=" + binding.governmentUuid() + ", account_id=" + binding.accountId(),
                        "Bank account UUID mismatch. stored_bank_account_uuid=" + binding.bankAccountUuid() +
                                ", current_bank_account_uuid=" + current.bankAccountUuid()
                ));
            }
            if (!binding.accountId().equals(current.bankAccountUuid())) {
                bindingMismatches++;
                findings.add(new DatabaseCheckFinding(
                        "economy_towny_governments",
                        "government_uuid=" + binding.governmentUuid() + ", account_id=" + binding.accountId(),
                        "Binding account_id does not match the current Towny bank account UUID. expected_account_id=" +
                                current.bankAccountUuid()
                ));
            }
            if (!Objects.equals(binding.governmentName(), current.governmentName())) {
                bindingMismatches++;
                findings.add(new DatabaseCheckFinding(
                        "economy_towny_governments",
                        "government_uuid=" + binding.governmentUuid() + ", account_id=" + binding.accountId(),
                        "Government name mismatch. stored_government_name=" + binding.governmentName() +
                                ", current_government_name=" + current.governmentName()
                ));
            }
            if (!Objects.equals(binding.bankAccountName(), current.bankAccountName())) {
                bindingMismatches++;
                findings.add(new DatabaseCheckFinding(
                        "economy_towny_governments",
                        "government_uuid=" + binding.governmentUuid() + ", account_id=" + binding.accountId(),
                        "Bank account name mismatch. stored_bank_account_name=" + binding.bankAccountName() +
                                ", current_bank_account_name=" + current.bankAccountName()
                ));
            }

            AccountRecord account = sharedAccountsById.get(binding.accountId());
            if (account == null) {
                bindingMismatches++;
                findings.add(new DatabaseCheckFinding(
                        "economy_accounts",
                        "account_id=" + binding.accountId() + ", government_uuid=" + binding.governmentUuid(),
                        "Towny binding references a missing shared account row."
                ));
                continue;
            }
            if (!Objects.equals(account.ownerUuid(), current.bankAccountUuid())) {
                bindingMismatches++;
                findings.add(new DatabaseCheckFinding(
                        "economy_accounts",
                        "account_id=" + account.accountId() + ", account_name=" + account.accountName(),
                        "Towny shared account owner_uuid mismatch. expected owner_uuid=" + current.bankAccountUuid()
                ));
            }
            if (!Objects.equals(account.accountName(), current.bankAccountName())) {
                bindingMismatches++;
                findings.add(new DatabaseCheckFinding(
                        "economy_accounts",
                        "account_id=" + account.accountId() + ", account_name=" + account.accountName(),
                        "Towny shared account name mismatch. expected account_name=" + current.bankAccountName()
                ));
            }
        }

        for (GovernmentSnapshot current : currentGovernments.values()) {
            GovernmentKey key = new GovernmentKey(current.governmentType(), current.governmentUuid());
            if (bindingsByGovernment.containsKey(key)) {
                continue;
            }
            missingBindings++;
            findings.add(new DatabaseCheckFinding(
                    "economy_towny_governments",
                    "government_type=" + current.governmentType() + ", government_uuid=" + current.governmentUuid(),
                    "Current Towny government is missing its EconomyPol binding row."
            ));
        }

        for (AccountRecord account : sharedAccounts) {
            if (bindingsByAccountId.containsKey(account.accountId())) {
                continue;
            }
            GovernmentSnapshot currentById = currentByBankAccountId.get(account.accountId());
            GovernmentSnapshot currentByName = currentByBankAccountName.get(normalize(account.accountName()));
            if (currentById != null) {
                findings.add(new DatabaseCheckFinding(
                        "economy_accounts",
                        "account_id=" + account.accountId() + ", account_name=" + account.accountName(),
                        "Towny-managed shared account exists without an economy_towny_governments binding row."
                ));
                continue;
            }
            if (!looksTownyManagedAccount(account.accountName()) && currentByName == null) {
                continue;
            }
            if (currentByName != null && !account.accountId().equals(currentByName.bankAccountUuid())) {
                legacyConflictingRows++;
                findings.add(new DatabaseCheckFinding(
                        "economy_accounts",
                        "account_id=" + account.accountId() + ", account_name=" + account.accountName(),
                        "Legacy Towny-style shared account conflicts with the current canonical Towny bank account UUID=" +
                                currentByName.bankAccountUuid()
                ));
                cleanupCandidates.add(new CleanupCandidate(
                        "LEGACY_CONFLICT",
                        account.accountId(),
                        account.accountName(),
                        "legacy Towny-style row conflicts with the current canonical Towny government account"
                ));
                continue;
            }

            legacyUnboundRows++;
            findings.add(new DatabaseCheckFinding(
                    "economy_accounts",
                    "account_id=" + account.accountId() + ", account_name=" + account.accountName(),
                    "Legacy Towny-style shared account no longer matches any current Towny government."
            ));
            cleanupCandidates.add(new CleanupCandidate(
                    "LEGACY_ORPHAN",
                    account.accountId(),
                    account.accountName(),
                    "legacy Towny-style row has no matching current Towny government"
            ));
        }

        return new InspectionSnapshot(
                bindings,
                currentGovernments,
                townBindings,
                nationBindings,
                orphanBindings,
                missingBindings,
                bindingMismatches,
                legacyUnboundRows,
                legacyConflictingRows,
                List.copyOf(findings),
                List.copyOf(cleanupCandidates)
        );
    }

    private Map<GovernmentKey, GovernmentSnapshot> currentGovernments() {
        Map<GovernmentKey, GovernmentSnapshot> governments = new LinkedHashMap<>();
        for (Town town : TownyAPI.getInstance().getTowns()) {
            putGovernmentSnapshot(governments, town);
        }
        for (Nation nation : TownyAPI.getInstance().getNations()) {
            putGovernmentSnapshot(governments, nation);
        }
        return governments;
    }

    private boolean looksTownyManagedAccount(String accountName) {
        for (TownyGovernmentType governmentType : TownyGovernmentType.values()) {
            if (governmentType.matchesAccountNamePrefix(accountName)) {
                return true;
            }
        }
        return false;
    }

    private String trimmedBankAccountName(TownyGovernmentType governmentType, String governmentName) {
        return governmentType.trimmedBankAccountName(governmentName, TOWNY_ACCOUNT_NAME_MAX_LENGTH);
    }

    private void putGovernmentSnapshot(Map<GovernmentKey, GovernmentSnapshot> governments, Government government) {
        GovernmentSnapshot snapshot = new GovernmentSnapshot(
                TownyGovernmentType.fromGovernment(government),
                government.getUUID(),
                government.getAccount().getUUID(),
                government.getName(),
                government.getAccount().getName()
        );
        governments.put(new GovernmentKey(snapshot.governmentType(), snapshot.governmentUuid()), snapshot);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record GovernmentKey(TownyGovernmentType governmentType, UUID governmentUuid) {
    }

    private record GovernmentSnapshot(
            TownyGovernmentType governmentType,
            UUID governmentUuid,
            UUID bankAccountUuid,
            String governmentName,
            String bankAccountName
    ) {
    }

    private record CleanupCandidate(String type, UUID accountId, String accountName, String reason) {
    }

    private record InspectionSnapshot(
            List<TownyGovernmentBinding> bindings,
            Map<GovernmentKey, GovernmentSnapshot> currentGovernments,
            long townBindings,
            long nationBindings,
            long orphanBindings,
            long missingBindings,
            long bindingMismatches,
            long legacyUnboundRows,
            long legacyConflictingRows,
            List<DatabaseCheckFinding> findings,
            List<CleanupCandidate> cleanupCandidates
    ) {

        long currentTownCount() {
            return currentGovernments.values().stream()
                    .filter(snapshot -> snapshot.governmentType() == TownyGovernmentType.TOWN)
                    .count();
        }

        long currentNationCount() {
            return currentGovernments.values().stream()
                    .filter(snapshot -> snapshot.governmentType() == TownyGovernmentType.NATION)
                    .count();
        }
    }
}
