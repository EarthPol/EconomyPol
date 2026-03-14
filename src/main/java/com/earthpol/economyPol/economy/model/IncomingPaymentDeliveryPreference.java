package com.earthpol.economyPol.economy.model;

import java.util.List;
import java.util.Locale;

public enum IncomingPaymentDeliveryPreference {
    DEFAULT(
            "default",
            "Inventory -> Ender chest -> Custodial",
            List.of(MoneyRouteTarget.INVENTORY, MoneyRouteTarget.ENDER_CHEST, MoneyRouteTarget.CUSTODIAL_ACCOUNT)
    ),
    SKIP_INVENTORY(
            "skipinventory",
            "Ender chest -> Custodial",
            List.of(MoneyRouteTarget.ENDER_CHEST, MoneyRouteTarget.CUSTODIAL_ACCOUNT)
    ),
    SKIP_INVENTORY_AND_ENDERCHEST(
            "skipinventoryandenderchest",
            "Custodial only",
            List.of(MoneyRouteTarget.CUSTODIAL_ACCOUNT)
    );

    private final String commandToken;
    private final String description;
    private final List<MoneyRouteTarget> effectiveRoutingOrder;

    IncomingPaymentDeliveryPreference(
            String commandToken,
            String description,
            List<MoneyRouteTarget> effectiveRoutingOrder
    ) {
        this.commandToken = commandToken;
        this.description = description;
        this.effectiveRoutingOrder = List.copyOf(effectiveRoutingOrder);
    }

    public String commandToken() {
        return commandToken;
    }

    public String description() {
        return description;
    }

    public List<MoneyRouteTarget> effectiveRoutingOrder() {
        return effectiveRoutingOrder;
    }

    public static IncomingPaymentDeliveryPreference fromCommandToken(String raw) {
        String normalized = raw == null ? "" : raw.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        for (IncomingPaymentDeliveryPreference value : values()) {
            if (value.commandToken.equals(normalized)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown incoming payment delivery preference: " + raw);
    }
}
