package com.earthpol.economyPol.domain;

public record PlayerAccountPolicy(
        boolean allowSelfDeposit,
        boolean allowExternalCredit,
        boolean allowSelfWithdraw
) {}
