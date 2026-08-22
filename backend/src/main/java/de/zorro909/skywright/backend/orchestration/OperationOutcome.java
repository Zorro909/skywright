package de.zorro909.skywright.backend.orchestration;

import java.util.List;

public sealed interface OperationOutcome {

	record Submitted(long jobId, ResourceHandle handle) implements OperationOutcome {
	}

	record Observed(List<ClusterStatus> clusters) implements OperationOutcome {
		public Observed {
			clusters = List.copyOf(clusters);
		}
	}

	record Controlled(boolean applied) implements OperationOutcome {
	}

	record Cleaned(boolean removed) implements OperationOutcome {
	}

	record Failed(String category, String message) implements OperationOutcome {
	}

	record ClusterStatus(String name, String status, ResourceHandle handle) {
	}

	record ResourceHandle(String type, String clusterName, String clusterNameOnCloud, int launchedNodes,
			String launchedResources) {
	}

}
