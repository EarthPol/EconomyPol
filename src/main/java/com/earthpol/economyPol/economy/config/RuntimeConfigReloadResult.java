package com.earthpol.economyPol.economy.config;

import java.util.List;

public record RuntimeConfigReloadResult(
        boolean success,
        String message,
        List<String> warnings
) {

    public static RuntimeConfigReloadResult success(String message, List<String> warnings) {
        return new RuntimeConfigReloadResult(true, message, List.copyOf(warnings));
    }

    public static RuntimeConfigReloadResult failure(String message) {
        return new RuntimeConfigReloadResult(false, message, List.of());
    }
}
