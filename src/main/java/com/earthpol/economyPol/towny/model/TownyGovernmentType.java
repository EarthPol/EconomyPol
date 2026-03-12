package com.earthpol.economyPol.towny.model;

import com.palmergames.bukkit.towny.TownySettings;
import com.palmergames.bukkit.towny.object.Government;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Town;

import java.util.Locale;

public enum TownyGovernmentType {
    TOWN(Town.class),
    NATION(Nation.class);

    private final Class<? extends Government> governmentClass;

    TownyGovernmentType(Class<? extends Government> governmentClass) {
        this.governmentClass = governmentClass;
    }

    public static TownyGovernmentType fromGovernment(Government government) {
        for (TownyGovernmentType type : values()) {
            if (type.governmentClass.isInstance(government)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported Towny government type: " + government.getClass().getName());
    }

    public String displayNameLower() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String accountPrefix() {
        return switch (this) {
            case TOWN -> TownySettings.getTownAccountPrefix();
            case NATION -> TownySettings.getNationAccountPrefix();
        };
    }

    public boolean matchesAccountNamePrefix(String accountName) {
        return normalize(accountName).startsWith(normalize(accountPrefix()));
    }

    public String trimmedBankAccountName(String governmentName, int maxLength) {
        String fullName = accountPrefix() + (governmentName == null ? "" : governmentName);
        return fullName.length() <= maxLength ? fullName : fullName.substring(0, maxLength);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
