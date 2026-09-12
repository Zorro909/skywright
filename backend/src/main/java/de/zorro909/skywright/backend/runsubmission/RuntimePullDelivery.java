package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.credential.RuntimePullProjection;

/** Only the target-side helper uses Kubernetes provisioning credentials. */
public interface RuntimePullDelivery {

	record Readiness(boolean available, boolean nodeReady, String gpuModel, int gpuCount, int freeGpuCount,
			boolean writerReady) {
	}

	default Readiness readiness(String kubernetesContext) {
		return new Readiness(false, false, "", 0, 0, false);
	}

	String namespace(String kubernetesContext);

	boolean installed(AcceptedRun run);

	void install(AcceptedRun run, RuntimePullProjection projection);

}
