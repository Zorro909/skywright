package de.zorro909.skywright.backend.trainingproject;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Embeddable
class RebindingArtifactEmbeddable {

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	ReferencedProjectArtifact.Kind kind;

	@Column(nullable = false)
	String repository;

	@Column(nullable = false)
	String digest;

	@Column(nullable = false)
	boolean verified;

	@Column(name = "failure_code")
	String failureCode;

	protected RebindingArtifactEmbeddable() {
	}

	static RebindingArtifactEmbeddable from(RebindingArtifact value) {
		var result = new RebindingArtifactEmbeddable();
		result.kind = value.kind();
		result.repository = value.repository();
		result.digest = value.digest();
		result.verified = value.verified();
		result.failureCode = value.failureCode();
		return result;
	}

	RebindingArtifact domain() {
		return new RebindingArtifact(this.kind, this.repository, this.digest, this.verified, this.failureCode);
	}

}
