package de.zorro909.skywright.backend.runstore;

import java.util.Objects;
import java.util.UUID;

/** Constructs and validates protocol-v1 semantic object identities. */
public final class RunStoreProtocol {

	private final String runPrefix;

	private final String runId;

	public RunStoreProtocol(String trainingProjectId, String runId) {
		this.runId = runId;
		this.runPrefix = component(trainingProjectId, "Training Project identity") + "/"
				+ component(runId, "Run identity") + "/v1/";
	}

	public String runId() {
		return this.runId;
	}

	public String runPrefix() {
		return this.runPrefix;
	}

	public String attemptRecordKey(String attemptId) {
		return this.runPrefix + "attempts/" + attempt(attemptId) + "/record.json";
	}

	public String attemptReportKey(String attemptId) {
		return this.runPrefix + "attempts/" + attempt(attemptId) + "/report.json";
	}

	public String checkpointKey(long step, String digest) {
		return this.runPrefix + "checkpoints/" + step(step) + "/" + digest(digest) + ".safetensors";
	}

	public String metricSegmentKey(String attemptId, long segment) {
		return this.runPrefix + "metrics/" + attempt(attemptId) + "/events.out.tfevents." + step(segment)
				+ ".skywright";
	}

	public String progressKey() {
		return this.runPrefix + "progress.json";
	}

	public String artifactKey(String attemptId, long step, String name) {
		return outputKey("artifacts", attemptId, step, name);
	}

	public String sampleKey(String attemptId, long step, String name) {
		return outputKey("samples", attemptId, step, name);
	}

	private String outputKey(String kind, String attemptId, long step, String name) {
		return this.runPrefix + kind + "/" + attempt(attemptId) + "/" + step(step) + "/" + outputName(name);
	}

	private static String outputName(String value) {
		if (value == null || value.isEmpty() || value.indexOf('\0') >= 0) {
			throw new IllegalArgumentException("Output name must be non-empty Unicode text");
		}
		return PercentCodec.encode(value);
	}

	private static String step(long value) {
		if (value < 0) {
			throw new IllegalArgumentException("Step must be non-negative");
		}
		return "%019d".formatted(value);
	}

	private static String attempt(String value) {
		try {
			if (!UUID.fromString(value).toString().equals(value)) {
				throw new IllegalArgumentException();
			}
			return value;
		}
		catch (RuntimeException failure) {
			throw new IllegalArgumentException("Execution Attempt identity must be canonical UUID text", failure);
		}
	}

	private static String digest(String value) {
		if (!Objects.requireNonNull(value, "digest").matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("SHA-256 digest must be lowercase hexadecimal");
		}
		return value;
	}

	private static String component(String value, String label) {
		if (value == null || value.isEmpty() || value.equals(".") || value.equals("..") || value.indexOf('/') >= 0
				|| value.indexOf('\0') >= 0) {
			throw new IllegalArgumentException(label + " must be a non-empty portable key component");
		}
		return PercentCodec.encode(value);
	}

}
