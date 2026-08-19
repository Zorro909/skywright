package de.zorro909.skywright.backend.targetstorage;

import de.zorro909.skywright.backend.runstore.RunStoreCapabilities;
import java.util.LinkedHashSet;
import java.util.List;

final class TargetStorageCapabilities {

	static final List<String> REQUIRED = RunStoreCapabilities.requiredS3Capabilities();

	private TargetStorageCapabilities() {
	}

	static boolean isCompleteSuccess(List<TargetStorageCapabilityResult> results) {
		return results.size() == REQUIRED.size()
				&& new LinkedHashSet<>(results.stream().map(TargetStorageCapabilityResult::capability).toList())
					.equals(new LinkedHashSet<>(REQUIRED))
				&& results.stream().allMatch(TargetStorageCapabilityResult::succeeded);
	}

}
