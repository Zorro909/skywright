package de.zorro909.skywright.backend.pricing;

import java.time.Instant;

public interface PriceSource {

	CurrencyConversionQuote resolveCurrencyConversion(String nativeCurrency, String reportingCurrency,
			Instant quoteTime);

}
