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

class PriceSourceConflictException extends PriceSourceException {

	PriceSourceConflictException(String code, String message) {
		super(code, message);
	}

}

final class PriceSourceValidationException extends PriceSourceException {

	PriceSourceValidationException(String code, String message) {
		super(code, message);
	}

}

final class GpuPriceScheduleEntryNotFoundException extends PriceSourceException {

	GpuPriceScheduleEntryNotFoundException() {
		super("GPU_PRICE_SCHEDULE_ENTRY_NOT_FOUND", "The GPU price schedule entry does not exist");
	}

}

final class GpuPriceScheduleOverlapException extends PriceSourceConflictException {

	GpuPriceScheduleOverlapException() {
		super("GPU_PRICE_SCHEDULE_OVERLAP", "GPU price schedule entries for one offering must not overlap");
	}

}

final class GpuPriceScheduleRevisionConflictException extends PriceSourceConflictException {

	GpuPriceScheduleRevisionConflictException() {
		super("GPU_PRICE_SCHEDULE_REVISION_CONFLICT", "The GPU price schedule entry changed; reload it and retry");
	}

}
