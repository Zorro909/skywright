package de.zorro909.skywright.backend.datasetpublication;

import java.net.URI;

public record DatasetPublicationWorkerJob(DatasetPublicationWorkerAction action, URI endpoint, String bucket,
		String region, boolean pathStyleAccess, boolean chunkedEncoding, String formatIdentity, String manifestIdentity,
		String contentFingerprint, long objectCount, long byteCount, String payloadLocation, String operationLocation,
		int verificationConcurrency) {
}
