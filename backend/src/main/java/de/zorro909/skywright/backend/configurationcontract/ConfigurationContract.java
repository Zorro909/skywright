package de.zorro909.skywright.backend.configurationcontract;

import java.util.List;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** A validated Project Configuration Contract ready to resolve submissions. */
public final class ConfigurationContract {

	private final ConfigurationContracts owner;

	private final Schema schema;

	private final ObjectNode defaults;

	ConfigurationContract(ConfigurationContracts owner, Schema schema, ObjectNode defaults) {
		this.owner = owner;
		this.schema = schema;
		this.defaults = defaults;
	}

	public JsonNode resolve(String submissionJson) {
		ObjectNode submission = this.owner.parseObject(submissionJson, "submission");
		ObjectNode resolved = ConfigurationContracts.overlay(this.defaults, submission);
		List<ConfigurationError> errors = this.schema.validate(resolved)
			.stream()
			.map(error -> schemaError(error, "submission"))
			.sorted()
			.toList();
		if (!errors.isEmpty()) {
			throw new ConfigurationContractException(errors);
		}
		return resolved;
	}

	static ConfigurationError schemaError(Error error, String source) {
		return new ConfigurationError("CONFIG_SCHEMA_VALIDATION", source, error.getInstanceLocation().toString(),
				error.getKeyword());
	}

}
