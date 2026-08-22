package de.zorro909.skywright.backend.datasetcatalog;

public abstract class DatasetCatalogException extends RuntimeException {

	private final String errorCode;

	protected DatasetCatalogException(String errorCode, String message) {
		super(errorCode + ": " + message);
		this.errorCode = errorCode;
	}

	public String errorCode() {
		return this.errorCode;
	}

}
