package de.zorro909.skywright.backend.pricing;

class PriceSourceException extends RuntimeException {

	private final String code;

	PriceSourceException(String code, String message) {
		super(code + ": " + message);
		this.code = code;
	}

	String code() {
		return this.code;
	}

}

final class PriceSourceNotFoundException extends PriceSourceException {

	PriceSourceNotFoundException() {
		super("PRICE_SOURCE_NOT_FOUND", "The Price Source does not exist");
	}

}

final class CurrencyConversionNotFoundException extends PriceSourceException {

	CurrencyConversionNotFoundException() {
		super("CURRENCY_CONVERSION_NOT_FOUND", "The currency conversion does not exist");
	}

}

final class PriceSourceConflictException extends PriceSourceException {

	PriceSourceConflictException(String code, String message) {
		super(code, message);
	}

}

final class PriceSourceValidationException extends PriceSourceException {

	PriceSourceValidationException(String code, String message) {
		super(code, message);
	}

}
