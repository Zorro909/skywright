package de.zorro909.skywright.backend.configurationcontract;

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
		this.owner.validate(this.schema, resolved, "submission");
		return resolved;
	}

}
