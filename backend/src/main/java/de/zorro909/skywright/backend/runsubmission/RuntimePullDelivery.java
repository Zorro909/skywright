package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.credential.RuntimePullProjection;

/** Only the target-side helper uses Kubernetes provisioning credentials. */
public interface RuntimePullDelivery {

	String namespace(String kubernetesContext);

	boolean installed(AcceptedRun run);

	void install(AcceptedRun run, RuntimePullProjection projection);

}
