package de.zorro909.skywright.backend.datasetpublication;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DatasetPublicationStorageFailureTest {

	@ParameterizedTest
	@EnumSource(value = DatasetPublicationState.class, names = { "VERIFYING", "ABORTING", "PUBLISHED_CLEANUP_PENDING" })
	void localStorageFailureIdentifiesTheWorkerDuringVerificationAndCleanup(DatasetPublicationState state) {
		var operation = new DatasetPublicationEntity();
		operation.publicationId = UUID.randomUUID();
		operation.state = state;
		EntityManager entities = (EntityManager) Proxy.newProxyInstance(EntityManager.class.getClassLoader(),
				new Class<?>[] { EntityManager.class }, (proxy, method, arguments) -> {
					if (method.getName().equals("find") && arguments[1].equals(operation.publicationId)) {
						return operation;
					}
					throw new UnsupportedOperationException(method.getName());
				});
		var service = new DatasetPublicationService(entities, null, null, null, Clock.systemUTC(), null,
				Duration.ofMinutes(2));
		var failure = DatasetPublicationWorkerLauncher.temporaryStorageFailure();
		if (state == DatasetPublicationState.VERIFYING) {
			service.fail(operation.publicationId, failure);
		}
		else {
			service.cleanupFailed(operation.publicationId, failure);
		}
		assertThat(operation.failureCode).isEqualTo("DATASET_WORKER_TEMPORARY_STORAGE_UNAVAILABLE");
		assertThat(operation.unavailableSource).isEqualTo("Dataset Verification Worker");
		assertThat(operation.failureDetail).isEqualTo("The Dataset worker could not write its temporary control files");
		assertThat(operation.retryable).isTrue();
		assertThat(operation.state).isIn(DatasetPublicationState.FAILED, DatasetPublicationState.FAILED_CLEANUP);
	}

}
