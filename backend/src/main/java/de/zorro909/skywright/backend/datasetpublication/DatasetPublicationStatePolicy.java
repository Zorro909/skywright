package de.zorro909.skywright.backend.datasetpublication;

import java.util.EnumSet;
import java.util.Set;

final class DatasetPublicationStatePolicy {

	private static final Set<DatasetPublicationState> COMMIT_BOUNDARY = EnumSet.of(DatasetPublicationState.COMMITTING,
			DatasetPublicationState.PUBLISHED_CLEANUP_PENDING, DatasetPublicationState.COMMITTED);

	private static final Set<DatasetPublicationState> CLEANUP = EnumSet.of(DatasetPublicationState.ABORTING,
			DatasetPublicationState.PUBLISHED_CLEANUP_PENDING);

	private DatasetPublicationStatePolicy() {
	}

	static boolean canResume(DatasetPublicationState state) {
		return state != DatasetPublicationState.ABORTING && state != DatasetPublicationState.ABORTED;
	}

	static boolean canRenewPreferredDefinitionDecision(DatasetPublicationEntity publication) {
		return publication.state == DatasetPublicationState.FAILED
				&& "DATASET_REVISION_STALE".equals(publication.failureCode);
	}

	static boolean abortAlreadyAccepted(DatasetPublicationState state) {
		return state == DatasetPublicationState.ABORTING || state == DatasetPublicationState.ABORTED;
	}

	static boolean crossedCommitBoundary(DatasetPublicationEntity publication) {
		return COMMIT_BOUNDARY.contains(publication.state)
				|| publication.state == DatasetPublicationState.FAILED_CLEANUP
						&& publication.preferredDefinitionId != null;
	}

	static boolean canCommit(DatasetPublicationState state) {
		return state == DatasetPublicationState.VERIFYING;
	}

	static boolean needsVerificationReconciliation(DatasetPublicationState state) {
		return verificationReconciliationStates().contains(state);
	}

	static Set<DatasetPublicationState> verificationReconciliationStates() {
		return EnumSet.of(DatasetPublicationState.VERIFYING);
	}

	static boolean needsCleanupReconciliation(DatasetPublicationState state) {
		return CLEANUP.contains(state);
	}

	static Set<DatasetPublicationState> cleanupReconciliationStates() {
		return EnumSet.copyOf(CLEANUP);
	}

}
