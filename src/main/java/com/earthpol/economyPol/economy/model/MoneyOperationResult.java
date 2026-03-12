package com.earthpol.economyPol.economy.model;

public record MoneyOperationResult(
        boolean success,
        long requestedAmount,
        long processedAmount,
        long remainder,
        String message
) {

    public static MoneyOperationResult success(long requestedAmount, long processedAmount, long remainder, String message) {
        return new MoneyOperationResult(true, requestedAmount, processedAmount, remainder, message);
    }

    public static MoneyOperationResult failure(long requestedAmount, String message) {
        return new MoneyOperationResult(false, requestedAmount, 0L, requestedAmount, message);
    }
}
