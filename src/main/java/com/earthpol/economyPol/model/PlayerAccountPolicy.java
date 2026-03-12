package com.earthpol.economyPol.model;

public record PlayerAccountPolicy(
        boolean allowSelfDeposit,
        boolean allowExternalCredit,
        boolean allowSelfWithdraw
) {}
