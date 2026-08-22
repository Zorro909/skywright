package de.zorro909.skywright.backend.rundefinition;

import java.util.Map;

/** One stable, non-sensitive reason a Run Submission or definition is invalid. */
public record RunDefinitionFailure(String code, String source, String pointer, String keyword,
		Map<String, String> details) implements Comparable<RunDefinitionFailure> {

	public RunDefinitionFailure {
		details = Map.copyOf(details);
	}

	public RunDefinitionFailure(String code, String source, String pointer, String keyword) {
		this(code, source, pointer, keyword, Map.of());
	}

	@Override
	public int compareTo(RunDefinitionFailure other) {
		int pointerOrder = this.pointer.compareTo(other.pointer);
		if (pointerOrder != 0) {
			return pointerOrder;
		}
		int codeOrder = this.code.compareTo(other.code);
		if (codeOrder != 0) {
			return codeOrder;
		}
		int sourceOrder = this.source.compareTo(other.source);
		if (sourceOrder != 0) {
			return sourceOrder;
		}
		int keywordOrder = this.keyword.compareTo(other.keyword);
		return keywordOrder != 0 ? keywordOrder : new java.util.TreeMap<>(this.details).toString()
			.compareTo(new java.util.TreeMap<>(other.details).toString());
	}

}
