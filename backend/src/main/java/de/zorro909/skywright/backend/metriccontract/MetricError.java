package de.zorro909.skywright.backend.metriccontract;

/** One language-independent Project Metric Contract failure. */
public record MetricError(String code, String source, String pointer,
		String keyword) implements Comparable<MetricError> {

	@Override
	public int compareTo(MetricError other) {
		int pointerOrder = this.pointer.compareTo(other.pointer);
		if (pointerOrder != 0) {
			return pointerOrder;
		}
		int codeOrder = this.code.compareTo(other.code);
		return codeOrder != 0 ? codeOrder : this.keyword.compareTo(other.keyword);
	}

}
