package com.earthpol.economyPol.economy.model;

public record PlayerAccountPolicy(
        boolean allowSelfDeposit,
        boolean allowExternalCredit,
        boolean allowSelfWithdraw
) {}
