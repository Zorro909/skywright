package de.zorro909.skywright.backend.runstore;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Location-independent identity of one immutable Checkpoint. */
public record CheckpointReference(long step, String digest) {

	private static final Pattern VALUE = Pattern
		.compile("skywright-checkpoint:v1:(0|[1-9][0-9]*):sha256:([0-9a-f]{64})");

	public CheckpointReference {
		if (step < 0) {
			throw new IllegalArgumentException("Step must be non-negative");
		}
		if (!Objects.requireNonNull(digest, "digest").matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("SHA-256 digest must be lowercase hexadecimal");
		}
	}

	public static CheckpointReference parse(String value) {
		Matcher matcher = VALUE.matcher(Objects.requireNonNull(value, "value"));
		if (!matcher.matches()) {
			throw new IllegalArgumentException("invalid checkpoint reference");
		}
		try {
			return new CheckpointReference(Long.parseLong(matcher.group(1)), matcher.group(2));
		}
		catch (NumberFormatException failure) {
			throw new IllegalArgumentException("invalid checkpoint reference: Step exceeds signed 64-bit", failure);
		}
	}

	@Override
	public String toString() {
		return "skywright-checkpoint:v1:" + this.step + ":sha256:" + this.digest;
	}

}
