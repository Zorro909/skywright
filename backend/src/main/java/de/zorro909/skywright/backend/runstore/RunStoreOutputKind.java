package de.zorro909.skywright.backend.runstore;

/** Closed immutable output kinds and their protocol key segments. */
public enum RunStoreOutputKind {

	ARTIFACT("artifacts", "artifact"), SAMPLE("samples", "sample");

	private final String keySegment;

	private final String metadataValue;

	RunStoreOutputKind(String keySegment, String metadataValue) {
		this.keySegment = keySegment;
		this.metadataValue = metadataValue;
	}

	public String keySegment() {
		return this.keySegment;
	}

	public String metadataValue() {
		return this.metadataValue;
	}

	public static RunStoreOutputKind fromKeySegment(String value) {
		for (RunStoreOutputKind kind : values()) {
			if (kind.keySegment.equals(value)) {
				return kind;
			}
		}
		throw new IllegalArgumentException("unknown Run Store output key segment");
	}

}
