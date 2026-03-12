package com.earthpol.economyPol.towny.model;

import com.palmergames.bukkit.towny.object.Government;
import com.palmergames.bukkit.towny.object.Nation;
import com.palmergames.bukkit.towny.object.Town;

public enum TownyGovernmentType {
    TOWN(Town.class),
    NATION(Nation.class);

    private final Class<? extends Government> governmentClass;

    TownyGovernmentType(Class<? extends Government> governmentClass) {
        this.governmentClass = governmentClass;
    }
    public TownyGovernmentType getGovernmentType(Government government) {
        if (government instanceof Town) {return TOWN;}
        else if (government instanceof Nation) {return NATION;}
    }

}
