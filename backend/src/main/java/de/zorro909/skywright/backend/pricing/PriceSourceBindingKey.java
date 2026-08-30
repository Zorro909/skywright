package de.zorro909.skywright.backend.pricing;

import de.zorro909.skywright.backend.target.TargetIdentity;
import java.util.Currency;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

sealed interface PriceSourceBindingKey
		permits PriceSourceBindingKey.CurrencyPair, PriceSourceBindingKey.TargetResource {

	Pattern TARGET = Pattern.compile("target:([^:]+):resource:([a-z][a-z0-9-]*)");

	Pattern CURRENCY = Pattern.compile("currency:([A-Z]{3}):([A-Z]{3})");

	String value();

	static PriceSourceBindingKey parse(String value) {
		Matcher target = TARGET.matcher(value == null ? "" : value);
		if (target.matches()) {
			TargetResource result = new TargetResource(TargetIdentity.fromBindingToken(target.group(1)),
					target.group(2));
			if (result.value().equals(value)) {
				return result;
			}
		}
		Matcher currency = CURRENCY.matcher(value == null ? "" : value);
		if (currency.matches()) {
			return new CurrencyPair(currency(currency.group(1)), currency(currency.group(2)));
		}
		throw new IllegalArgumentException("Price Source binding key is invalid");
	}

	static TargetResource gpuCompute(TargetIdentity target) {
		return new TargetResource(target, "gpu-compute");
	}

	static CurrencyPair currencyPair(String nativeCurrency, String reportingCurrency) {
		return new CurrencyPair(currency(nativeCurrency), currency(reportingCurrency));
	}

	private static String currency(String value) {
		if (value == null || !value.matches("[A-Z]{3}")) {
			throw new IllegalArgumentException("Currency is invalid");
		}
		return Currency.getInstance(value).getCurrencyCode();
	}

	record TargetResource(TargetIdentity target, String resourceFamily) implements PriceSourceBindingKey {

		public TargetResource {
			if (target == null || resourceFamily == null || !resourceFamily.matches("[a-z][a-z0-9-]*")) {
				throw new IllegalArgumentException("Target resource binding is invalid");
			}
		}

		@Override
		public String value() {
			return "target:" + this.target.bindingToken() + ":resource:" + this.resourceFamily;
		}

	}

	record CurrencyPair(String nativeCurrency, String reportingCurrency) implements PriceSourceBindingKey {

		@Override
		public String value() {
			return "currency:" + this.nativeCurrency + ":" + this.reportingCurrency;
		}

	}

}
