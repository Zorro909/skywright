package de.zorro909.skywright.backend.datasetpublication;

interface DatasetPublicationWorkerRecoveryScheduler {

	void retry(Runnable action, int attempt);

}
