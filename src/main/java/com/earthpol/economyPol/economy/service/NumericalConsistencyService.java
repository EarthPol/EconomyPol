package com.earthpol.economyPol.economy.service;

import com.earthpol.economyPol.economy.config.PluginSettings;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class NumericalConsistencyService {

    private static final String FRACTIONAL_UNSUPPORTED = "Fractional amounts are not supported.";
    private static final String NON_FINITE_UNSUPPORTED = "Amount must be a finite number.";
    private static final String OUT_OF_RANGE = "Amount is out of range.";

    private final PluginSettings.NumericSettings settings;
    private final DenominationService denominationService;

    public NumericalConsistencyService(
            PluginSettings.NumericSettings settings,
            DenominationService denominationService
    ) {
        this.settings = settings;
        this.denominationService = denominationService;
    }

    public ConversionResult toWholeUnits(BigDecimal amount) {
        if (amount == null) {
            return ConversionResult.failure("Amount is missing.");
        }
        try {
            BigDecimal normalized = amount.stripTrailingZeros();
            if (normalized.scale() <= 0) {
                return ConversionResult.success(normalized.longValueExact(), false);
            }
            return switch (settings.decimalHandlingMode()) {
                case REJECT -> ConversionResult.failure(FRACTIONAL_UNSUPPORTED);
                case ROUND -> coerce(amount, settings.roundingMode());
                case TRUNCATE -> coerce(amount, RoundingMode.DOWN);
            };
        } catch (ArithmeticException exception) {
            return ConversionResult.failure(OUT_OF_RANGE);
        }
    }

    public ConversionResult toWholeUnits(double amount) {
        if (!Double.isFinite(amount)) {
            return ConversionResult.failure(NON_FINITE_UNSUPPORTED);
        }
        return toWholeUnits(BigDecimal.valueOf(amount));
    }

    public BigDecimal toBigDecimal(long amount) {
        return BigDecimal.valueOf(amount);
    }

    public double toDouble(long amount) {
        return amount;
    }

    public String format(BigDecimal amount) {
        if (amount == null) {
            return denominationService.format(0L);
        }
        ConversionResult conversion = toWholeUnits(amount);
        if (conversion.success()) {
            return denominationService.format(conversion.units());
        }
        return rawDecimalFormat(amount.stripTrailingZeros().toPlainString(), amount.compareTo(BigDecimal.ONE) == 0);
    }

    public String format(double amount) {
        if (!Double.isFinite(amount)) {
            return rawDecimalFormat(Double.toString(amount), false);
        }
        ConversionResult conversion = toWholeUnits(amount);
        if (conversion.success()) {
            return denominationService.format(conversion.units());
        }
        return rawDecimalFormat(Double.toString(amount), amount == 1D);
    }

    public int fractionalDigits() {
        return 0;
    }

    private ConversionResult coerce(BigDecimal amount, RoundingMode roundingMode) {
        try {
            return ConversionResult.success(amount.setScale(0, roundingMode).longValueExact(), true);
        } catch (ArithmeticException exception) {
            return ConversionResult.failure(OUT_OF_RANGE);
        }
    }

    private String rawDecimalFormat(String value, boolean singular) {
        return value + " " + (singular ? denominationService.singularName() : denominationService.pluralName());
    }

    public record ConversionResult(
            boolean success,
            long units,
            boolean coerced,
            String message
    ) {

        public static ConversionResult success(long units, boolean coerced) {
            return new ConversionResult(true, units, coerced, "");
        }

        public static ConversionResult failure(String message) {
            return new ConversionResult(false, 0L, false, message);
        }
    }
}
