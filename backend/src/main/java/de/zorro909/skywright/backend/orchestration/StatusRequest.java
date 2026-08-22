package de.zorro909.skywright.backend.orchestration;

import java.util.List;

public record StatusRequest(List<String> jobNames) {

	public StatusRequest {
		jobNames = List.copyOf(jobNames);
	}

}
