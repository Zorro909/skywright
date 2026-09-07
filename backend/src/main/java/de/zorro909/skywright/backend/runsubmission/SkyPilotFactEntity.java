package de.zorro909.skywright.backend.runsubmission;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import de.zorro909.skywright.backend.orchestration.RetainedSkyPilotFact;
import tools.jackson.databind.json.JsonMapper;

@Entity
@Table(name = "retained_skypilot_fact", schema = "skywright")
class SkyPilotFactEntity {

	@Id
	UUID id;

	@Column(name = "run_id", nullable = false, updatable = false)
	UUID runId;

	@Column(name = "fact_kind", nullable = false, updatable = false)
	String kind;

	@Column(name = "source_event_identity", nullable = false, updatable = false)
	String sourceEventIdentity;

	@Column(name = "payload_json", nullable = false, updatable = false, columnDefinition = "text")
	String payload;

	@Column(name = "observed_at", nullable = false, updatable = false)
	Instant observedAt;

	@Column(name = "payload_digest", nullable = false, updatable = false)
	String payloadDigest;

	protected SkyPilotFactEntity() {
	}

	SkyPilotFactEntity(RetainedSkyPilotFact fact) {
		id = UUID.randomUUID();
		payloadDigest = payloadDigest(fact.payload());
		runId = fact.runId();
		kind = fact.kind().name();
		sourceEventIdentity = fact.sourceEventIdentity();
		payload = JsonMapper.builder().build().writeValueAsString(fact.payload());
		observedAt = fact.observedAt();
	}

	static String payloadDigest(java.util.Map<String, String> payload) {
		try {
			return java.util.HexFormat.of()
				.formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(JsonMapper.builder().build().writeValueAsBytes(new java.util.TreeMap<>(payload))));
		}
		catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

}
