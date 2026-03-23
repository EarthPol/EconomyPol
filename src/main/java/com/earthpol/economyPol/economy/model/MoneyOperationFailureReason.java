package com.earthpol.economyPol.economy.model;

/**
 * Structured machine-readable failure reasons for native EconomyPol money operations.
 */
public enum MoneyOperationFailureReason {
    NONE,
    UNKNOWN,
    NEGATIVE_AMOUNT,
    ACCOUNT_NOT_FOUND,
    INSUFFICIENT_FUNDS,
    PLAYER_MONEY_LOCKED,
    PLAYER_MONEY_ACCESS_UNAVAILABLE,
    INVALID_ROUTING_ORDER,
    NO_LIVE_MONEY_AVAILABLE,
    NO_INVENTORY_SPACE,
    NOT_ENOUGH_ROOM_FOR_CHANGE,
    OFFLINE_ENDER_WALLET_MISSING,
    OFFLINE_ENDER_WALLET_UNAVAILABLE,
    DELIVERY_FAILED,
    BALANCE_FINALIZATION_FAILED,
    CHANGE_ROUTING_FAILED
}
