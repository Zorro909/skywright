package de.zorro909.skywright.backend.trainingproject;

import java.util.List;
import java.util.UUID;

record TrainingProjectView(UUID id, String displayName, long revision, RegistryBinding activeBinding,
		List<RegistryBinding> bindingHistory) {

	TrainingProjectView {
		bindingHistory = List.copyOf(bindingHistory);
	}

}
