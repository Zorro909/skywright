package de.zorro909.skywright.backend.orchestration;

import java.util.List;

public sealed interface OperationOutcome {

	record Submitted(long jobId, ResourceHandle handle) implements OperationOutcome {
	}

	record Observed(List<ManagedJobStatus> jobs) implements OperationOutcome {
		public Observed {
			jobs = List.copyOf(jobs);
		}
	}

	record Controlled(boolean applied) implements OperationOutcome {
	}

	record Cleaned(boolean removed) implements OperationOutcome {
	}

	record Failed(String category, String message) implements OperationOutcome {
	}

	record ManagedJobStatus(long jobId, String jobName, String status, int recoveryCount) {
	}

	record ResourceHandle(String type, String clusterName, String clusterNameOnCloud, int launchedNodes,
			String launchedResources) {
	}

}
