package de.zorro909.skywright.backend.gpuoffering;

class GpuOfferingException extends RuntimeException {

	private final String code;

	GpuOfferingException(String code, String message) {
		super(code + ": " + message);
		this.code = code;
	}

	String code() {
		return this.code;
	}

}

final class GpuOfferingNotFoundException extends GpuOfferingException {

	GpuOfferingNotFoundException() {
		super("GPU_OFFERING_NOT_FOUND", "The Eligible GPU Offering does not exist");
	}

}

final class GpuOfferingConflictException extends GpuOfferingException {

	GpuOfferingConflictException() {
		super("GPU_OFFERING_REVISION_CONFLICT", "The Eligible GPU Offering changed; reload it and retry");
	}

}

final class GpuOfferingValidationException extends GpuOfferingException {

	GpuOfferingValidationException(String code, String message) {
		super(code, message);
	}

}
