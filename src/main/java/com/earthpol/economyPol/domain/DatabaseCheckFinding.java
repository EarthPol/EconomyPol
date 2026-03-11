package com.earthpol.economyPol.domain;

public record DatabaseCheckFinding(
        String tableName,
        String rowReference,
        String issue
) {

    @Override
    public String toString() {
        return tableName + "[" + rowReference + "]: " + issue;
    }
}
