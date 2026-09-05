package de.zorro909.skywright.backend.credential;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Operator-owned, non-secret evidence for one exact Vault KV v2 revision. */
public record CredentialBinding(UUID id, long revision, String path, Kind kind, String resource, String role,
		String identity, String scope, String accessProfile, Instant validatedAt, Instant validUntil,
		boolean nonExpiring) {

	public enum Kind {

		S3, GHCR, KUBERNETES, SKYPILOT

	}

	public CredentialBinding {
		if (id == null || revision < 1 || kind == null || !safePath(path) || blank(resource) || blank(identity)
				|| blank(scope) || blank(accessProfile) || validatedAt == null || nonExpiring == (validUntil != null)
				|| (validUntil != null && !validUntil.isAfter(validatedAt)) || !roles(kind).contains(role)) {
			throw new IllegalArgumentException("Invalid Credential Binding metadata");
		}
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	private static boolean safePath(String value) {
		return value != null && value.matches("[a-zA-Z0-9_-]+(/[a-zA-Z0-9_-]+)*");
	}

	private static Set<String> roles(Kind kind) {
		return switch (kind) {
			case S3 -> Set.of("backend", "training-process", "transfer-worker");
			case GHCR -> Set.of("backend-resolver", "execution-target-pull");
			case KUBERNETES -> Set.of("skypilot-api-server");
			case SKYPILOT -> Set.of("backend");
		};
	}
}
