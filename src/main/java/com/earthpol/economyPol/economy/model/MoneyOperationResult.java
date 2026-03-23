package com.earthpol.economyPol.economy.model;

public record MoneyOperationResult(
        boolean success,
        long requestedAmount,
        long processedAmount,
        long remainder,
        String message,
        MoneyOperationFailureReason failureReason
) {

    public static MoneyOperationResult success(long requestedAmount, long processedAmount, long remainder, String message) {
        return new MoneyOperationResult(
                true,
                requestedAmount,
                processedAmount,
                remainder,
                message,
                MoneyOperationFailureReason.NONE
        );
    }

    public static MoneyOperationResult failure(long requestedAmount, String message) {
        return failure(requestedAmount, message, MoneyOperationFailureReason.UNKNOWN);
    }

    public static MoneyOperationResult failure(
            long requestedAmount,
            String message,
            MoneyOperationFailureReason failureReason
    ) {
        return new MoneyOperationResult(
                false,
                requestedAmount,
                0L,
                requestedAmount,
                message,
                failureReason
        );
    }
}
