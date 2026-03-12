package com.earthpol.economyPol.model;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record DatabaseCheckReport(
        String reportName,
        Instant ranAt,
        long durationMillis,
        boolean healthy,
        String summary,
        Map<String, Long> statistics,
        List<String> notes,
        List<DatabaseCheckFinding> findings
) {

    public DatabaseCheckReport {
        statistics = Collections.unmodifiableMap(new LinkedHashMap<>(statistics));
        notes = List.copyOf(notes);
        findings = List.copyOf(findings);
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        builder.append("DatabaseCheckReport{")
                .append("reportName='").append(reportName).append('\'')
                .append(", ranAt=").append(ranAt)
                .append(", durationMillis=").append(durationMillis)
                .append(", healthy=").append(healthy)
                .append(", summary='").append(summary).append('\'');

        if (!statistics.isEmpty()) {
            builder.append(", statistics=").append(statistics);
        }
        if (!notes.isEmpty()) {
            builder.append(", notes=").append(notes);
        }
        if (!findings.isEmpty()) {
            builder.append(", findings=").append(findings);
        }
        builder.append('}');
        return builder.toString();
    }

    public Component toChatMessage() {
        return toChatMessage(findings.size());
    }

    public Component toChatMessage(int findingLimit) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("Database Check: ", NamedTextColor.GOLD)
                .append(Component.text(reportName, NamedTextColor.YELLOW))
                .append(Component.text(" [" + (healthy ? "HEALTHY" : "UNHEALTHY") + "]",
                        healthy ? NamedTextColor.GREEN : NamedTextColor.RED)));
        lines.add(Component.text("Ran: ", NamedTextColor.GRAY)
                .append(Component.text(ranAt.toString(), NamedTextColor.WHITE))
                .append(Component.text(" | Duration: ", NamedTextColor.GRAY))
                .append(Component.text(durationMillis + " ms", NamedTextColor.WHITE)));
        lines.add(Component.text("Summary: ", NamedTextColor.GRAY)
                .append(Component.text(summary, NamedTextColor.WHITE)));

        if (!statistics.isEmpty()) {
            lines.add(Component.text("Statistics:", NamedTextColor.AQUA));
            statistics.forEach((key, value) -> lines.add(Component.text(" - " + key + ": ", NamedTextColor.DARK_AQUA)
                    .append(Component.text(Long.toString(value), NamedTextColor.WHITE))));
        }
        if (!notes.isEmpty()) {
            lines.add(Component.text("Notes:", NamedTextColor.BLUE));
            notes.forEach(note -> lines.add(Component.text(" - " + note, NamedTextColor.WHITE)));
        }
        if (!findings.isEmpty()) {
            lines.add(Component.text("Findings:", NamedTextColor.RED));
            int cappedLimit = Math.max(0, findingLimit);
            int shown = Math.min(cappedLimit, findings.size());
            for (int index = 0; index < shown; index++) {
                DatabaseCheckFinding finding = findings.get(index);
                lines.add(Component.text(" - " + finding.tableName() + "[" + finding.rowReference() + "]: ", NamedTextColor.RED)
                        .append(Component.text(finding.issue(), NamedTextColor.WHITE)));
            }
            if (shown < findings.size()) {
                lines.add(Component.text(" - ... " + (findings.size() - shown) + " more findings omitted", NamedTextColor.YELLOW));
            }
        }

        return Component.join(JoinConfiguration.separator(Component.newline()), lines);
    }
}
