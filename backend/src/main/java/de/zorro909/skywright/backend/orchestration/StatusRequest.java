package de.zorro909.skywright.backend.orchestration;

import java.util.List;

public record StatusRequest(List<String> clusterNames) {

	public StatusRequest {
		clusterNames = List.copyOf(clusterNames);
	}

}
