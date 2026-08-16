package de.zorro909.skywright.backend.metriccontract;

import tools.jackson.databind.node.ObjectNode;

/** Validated canonical Project Metric Contract. */
public final class MetricContract {

	private final MetricContracts contracts;

	private final ObjectNode artifact;

	private final String canonicalJson;

	private final String digest;

	MetricContract(MetricContracts contracts, ObjectNode artifact, String canonicalJson, String digest) {
		this.contracts = contracts;
		this.artifact = artifact;
		this.canonicalJson = canonicalJson;
		this.digest = digest;
	}

	public String canonicalJson() {
		return this.canonicalJson;
	}

	public String digest() {
		return this.digest;
	}

	public MetricCatalog catalog(String projectIdentity, String projectVersion) {
		return this.contracts.catalog(this.artifact, this.digest, projectIdentity, projectVersion);
	}

}
