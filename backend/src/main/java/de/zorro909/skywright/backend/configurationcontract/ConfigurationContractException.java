package de.zorro909.skywright.backend.configurationcontract;

import java.util.Collection;
import java.util.List;

/** Deterministic failures that make a contract or submission invalid. */
public final class ConfigurationContractException extends IllegalArgumentException {

	private final List<ConfigurationError> errors;

	ConfigurationContractException(Collection<ConfigurationError> errors) {
		super(errors.stream()
			.map(ConfigurationError::code)
			.sorted()
			.reduce((left, right) -> left + "; " + right)
			.orElse("configuration contract failure"));
		this.errors = errors.stream().sorted().toList();
	}

	public List<ConfigurationError> errors() {
		return this.errors;
	}

}
