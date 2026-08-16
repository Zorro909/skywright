package de.zorro909.skywright.backend.configurationcontract;

/** One language-independent configuration failure. */
public record ConfigurationError(String code, String source, String pointer,
		String keyword) implements Comparable<ConfigurationError> {

	@Override
	public int compareTo(ConfigurationError other) {
		int pointerOrder = this.pointer.compareTo(other.pointer);
		if (pointerOrder != 0) {
			return pointerOrder;
		}
		int codeOrder = this.code.compareTo(other.code);
		return codeOrder != 0 ? codeOrder : this.keyword.compareTo(other.keyword);
	}

}
