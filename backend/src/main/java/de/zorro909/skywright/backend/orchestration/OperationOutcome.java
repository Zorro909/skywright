package de.zorro909.skywright.backend.orchestration;

import java.util.List;

public sealed interface OperationOutcome {

	record Submitted(long jobId, ResourceHandle handle) implements OperationOutcome {
	}

	record Observed(List<ManagedJobStatus> jobs, boolean complete) implements OperationOutcome {
		public Observed(List<ManagedJobStatus> jobs) {
			this(jobs, true);
		}

		public Observed {
			jobs = List.copyOf(jobs);
		}
	}

	record Controlled(boolean requestCompleted) implements OperationOutcome {
	}

	record Cleaned(boolean requestCompleted) implements OperationOutcome {
	}

	record Failed(String category, String message) implements OperationOutcome {
	}

	record ManagedJobStatus(Long jobId, String jobName, String status, Integer recoveryCount, Integer taskId,
			Double submittedAt, Double startedAt, Double endedAt, Double lastRecoveredAt, String runTimestamp,
			String cloud, String region, String zone, String resources, String failureReason, String clusterName) {
		public ManagedJobStatus(long jobId, String jobName, String status, int recoveryCount) {
			this(jobId, jobName, status, recoveryCount, null, null, null, null, null, null, null, null, null, null,
					null, null);
		}
	}

	record ResourceHandle(String type, String clusterName, String clusterNameOnCloud, int launchedNodes,
			String launchedResources) {
	}

}
