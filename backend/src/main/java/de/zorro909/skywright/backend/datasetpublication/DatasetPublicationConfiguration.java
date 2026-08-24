package de.zorro909.skywright.backend.datasetpublication;

import de.zorro909.skywright.backend.targetstorage.TargetStorageResolver;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DatasetPublicationConfiguration {

	@Bean
	Clock datasetPublicationClock() {
		return Clock.systemUTC();
	}

	@Bean
	DatasetPublicationCommitGate datasetPublicationCommitGate() {
		return datasetId -> {
		};
	}

	@Bean
	DatasetPublicationWorkerLauncher datasetPublicationWorkerLauncher(TargetStorageResolver targetStorages,
			DatasetPublicationCredentialProjectionLifecycle projections,
			@Value("${skywright.dataset-publication.verification-concurrency:4}") int verificationConcurrency) {
		return new DatasetPublicationWorkerLauncher(targetStorages, projections, verificationConcurrency);
	}

}
