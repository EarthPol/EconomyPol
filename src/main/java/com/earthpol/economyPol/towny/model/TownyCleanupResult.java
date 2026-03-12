package com.earthpol.economyPol.towny.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record TownyCleanupResult(
        boolean available,
        Instant ranAt,
        long durationMillis,
        String summary,
        Map<String, Long> statistics,
        List<String> notes,
        List<String> deletedEntries
) {

    public static TownyCleanupResult unavailable(String message) {
        return new TownyCleanupResult(false, Instant.now(), 0L, message, Map.of(), List.of(message), List.of());
    }

    public List<String> toChatLines() {
        List<String> lines = new ArrayList<>();
        lines.add(summary);
        statistics.forEach((key, value) -> lines.add("  " + key + ": " + value));
        for (String note : notes) {
            lines.add("  Note: " + note);
        }
        if (!deletedEntries.isEmpty()) {
            lines.add("  Deleted entries:");
            for (String deletedEntry : deletedEntries) {
                lines.add("    " + deletedEntry);
            }
        }
        lines.add("  Ran at: " + ranAt);
        lines.add("  Duration: " + durationMillis + "ms");
        return lines;
    }

    @Override
    public String toString() {
        return "TownyCleanupResult{" +
                "available=" + available +
                ", ranAt=" + ranAt +
                ", durationMillis=" + durationMillis +
                ", summary='" + summary + '\'' +
                ", statistics=" + statistics +
                ", notes=" + notes +
                ", deletedEntries=" + deletedEntries +
                '}';
    }
}
