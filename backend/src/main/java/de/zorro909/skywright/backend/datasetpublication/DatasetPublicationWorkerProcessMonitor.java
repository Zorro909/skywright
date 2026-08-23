package de.zorro909.skywright.backend.datasetpublication;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
interface DatasetPublicationWorkerProcessMonitor {

	Optional<CompletableFuture<Void>> completion(DatasetPublicationOpenCredentialProjection projection);

}
