package de.zorro909.skywright.backend.datasetcatalog;

public final class DatasetStorageUnavailableException extends DatasetCatalogException {

	private final String source;

	public DatasetStorageUnavailableException(String source, String summary) {
		super("DATASET_STORAGE_UNAVAILABLE", summary);
		this.source = source;
	}

	public String source() {
		return this.source;
	}

}
