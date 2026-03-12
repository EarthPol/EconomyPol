package com.earthpol.economyPol.service;

import com.earthpol.economyPol.model.DatabaseCheckFinding;

import java.util.List;
import java.util.Map;

public record TownyAccountScanResult(
        boolean available,
        String summary,
        Map<String, Long> statistics,
        List<String> notes,
        List<DatabaseCheckFinding> findings
) {

    public static TownyAccountScanResult unavailable(String message) {
        return new TownyAccountScanResult(false, message, Map.of(), List.of(message), List.of());
    }
}
