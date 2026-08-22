package de.zorro909.skywright.backend.datasetcatalog;

public enum DatasetCopyOperationProgress {

	WAITING_FOR_LEASES, TRANSFERRING, VERIFYING, PUBLISHING, DELETING_OLD_BYTES, COMPLETED, CANCELLED, FAILED

}
