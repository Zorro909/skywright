package de.zorro909.skywright.backend.runstore;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Validated current progress projection for one Run. */
public record ProgressRecord(int schemaVersion, String runId, long currentStep, Long latestDurableStep,
		String latestDurableCheckpoint, Long targetStep, Instant writtenAt) {

	private static final Set<String> REQUIRED = Set.of("schemaVersion", "runId", "currentStep", "latestDurableStep",
			"latestDurableCheckpoint", "writtenAt");

	private static final Set<String> ALLOWED = Set.of("schemaVersion", "runId", "currentStep", "latestDurableStep",
			"latestDurableCheckpoint", "targetStep", "writtenAt");

	public static ProgressRecord decode(byte[] body) {
		try {
			JsonNode value = JsonMapper.builder().build().readTree(body);
			if (!value.isObject()) {
				throw malformed("expected an object");
			}
			if (!value.path("schemaVersion").isInt() || value.path("schemaVersion").asInt() != 1) {
				throw new RunStoreIntegrityException("RUN_STORE_INCOMPATIBLE_SCHEMA: unknown Progress schema");
			}
			Set<String> names = value.propertyStream()
				.map(java.util.Map.Entry::getKey)
				.collect(java.util.stream.Collectors.toSet());
			if (!names.containsAll(REQUIRED) || !ALLOWED.containsAll(names)) {
				throw malformed("invalid members");
			}
			String runId = requiredText(value, "runId");
			long currentStep = requiredStep(value, "currentStep");
			Long durableStep = optionalStep(value, "latestDurableStep");
			String checkpoint = optionalText(value, "latestDurableCheckpoint");
			Long targetStep = optionalStep(value, "targetStep");
			if ((durableStep == null) != (checkpoint == null) || durableStep != null && durableStep > currentStep) {
				throw malformed("invalid durable state");
			}
			if (checkpoint != null && CheckpointReference.parse(checkpoint).step() != durableStep) {
				throw malformed("Checkpoint Reference differs from Durable Safe Point");
			}
			String writtenAt = requiredText(value, "writtenAt");
			if (!writtenAt.endsWith("Z")) {
				throw malformed("write time is not UTC");
			}
			return new ProgressRecord(1, runId, currentStep, durableStep, checkpoint, targetStep,
					Instant.parse(writtenAt));
		}
		catch (RunStoreIntegrityException failure) {
			throw failure;
		}
		catch (DateTimeParseException failure) {
			throw new RunStoreIntegrityException("RUN_STORE_MALFORMED_PROGRESS: invalid encoding", failure);
		}
		catch (RuntimeException failure) {
			throw new RunStoreIntegrityException("RUN_STORE_MALFORMED_PROGRESS: invalid encoding", failure);
		}
	}

	private static long requiredStep(JsonNode value, String name) {
		Long step = optionalStep(value, name);
		if (step == null) {
			throw malformed("missing " + name);
		}
		return step;
	}

	private static Long optionalStep(JsonNode value, String name) {
		JsonNode field = value.get(name);
		if (field == null || field.isNull()) {
			return null;
		}
		if (!field.isIntegralNumber() || !field.canConvertToLong() || field.asLong() < 0) {
			throw malformed("invalid " + name);
		}
		return field.asLong();
	}

	private static String requiredText(JsonNode value, String name) {
		String text = optionalText(value, name);
		if (text == null || text.isEmpty()) {
			throw malformed("invalid " + name);
		}
		return text;
	}

	private static String optionalText(JsonNode value, String name) {
		JsonNode field = value.get(name);
		if (field == null || field.isNull()) {
			return null;
		}
		if (!field.isTextual()) {
			throw malformed("invalid " + name);
		}
		return field.asText();
	}

	private static RunStoreIntegrityException malformed(String detail) {
		return new RunStoreIntegrityException("RUN_STORE_MALFORMED_PROGRESS: " + detail);
	}

}
