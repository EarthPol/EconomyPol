package com.earthpol.economyPol.service;

import com.earthpol.economyPol.economy.config.PluginSettings;
import com.earthpol.economyPol.economy.model.Denomination;
import com.earthpol.economyPol.economy.service.DenominationService;
import com.earthpol.economyPol.economy.service.NumericalConsistencyService;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NumericalConsistencyServiceTest {

    @Test
    void rejectModeRefusesFractionalAmounts() {
        NumericalConsistencyService service = newService(PluginSettings.DecimalHandlingMode.REJECT, RoundingMode.HALF_UP);

        NumericalConsistencyService.ConversionResult result = service.toWholeUnits(new BigDecimal("5.5"));

        assertFalse(result.success());
        assertEquals("Fractional amounts are not supported.", result.message());
    }

    @Test
    void roundModeUsesConfiguredRoundingMode() {
        NumericalConsistencyService service = newService(PluginSettings.DecimalHandlingMode.ROUND, RoundingMode.HALF_UP);

        NumericalConsistencyService.ConversionResult result = service.toWholeUnits(new BigDecimal("5.5"));

        assertTrue(result.success());
        assertEquals(6L, result.units());
        assertTrue(result.coerced());
        assertEquals("6 Gold Coins", service.format(new BigDecimal("5.5")));
    }

    @Test
    void truncateModeTruncatesTowardZero() {
        NumericalConsistencyService service = newService(PluginSettings.DecimalHandlingMode.TRUNCATE, RoundingMode.HALF_UP);

        NumericalConsistencyService.ConversionResult positive = service.toWholeUnits(new BigDecimal("5.9"));
        NumericalConsistencyService.ConversionResult negative = service.toWholeUnits(new BigDecimal("-5.9"));

        assertTrue(positive.success());
        assertEquals(5L, positive.units());
        assertTrue(negative.success());
        assertEquals(-5L, negative.units());
    }

    @Test
    void wholeNumberConversionsRemainExact() {
        NumericalConsistencyService service = newService(PluginSettings.DecimalHandlingMode.REJECT, RoundingMode.HALF_UP);

        NumericalConsistencyService.ConversionResult result = service.toWholeUnits(12.0D);

        assertTrue(result.success());
        assertEquals(12L, result.units());
        assertFalse(result.coerced());
        assertEquals(new BigDecimal("12"), service.toBigDecimal(12L));
        assertEquals(12D, service.toDouble(12L));
    }

    private static NumericalConsistencyService newService(
            PluginSettings.DecimalHandlingMode handlingMode,
            RoundingMode roundingMode
    ) {
        DenominationService denominationService = new DenominationService(
                new PluginSettings.CurrencySettings(
                        "Gold Coin",
                        "Gold Coins",
                        List.of(
                                new Denomination(Material.GOLD_NUGGET, 1L),
                                new Denomination(Material.GOLD_INGOT, 9L),
                                new Denomination(Material.GOLD_BLOCK, 81L)
                        )
                ),
                null
        );
        return new NumericalConsistencyService(
                new PluginSettings.NumericSettings(handlingMode, roundingMode),
                denominationService
        );
    }
}
