package de.zorro909.skywright.backend.datasetpublication;

import de.zorro909.skywright.backend.targetstorage.TargetStorageResolver;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DatasetPublicationConfiguration {

	@Bean
	Clock datasetPublicationClock() {
		return Clock.systemUTC();
	}

	@Bean
	DatasetPublicationWorkerLauncher datasetPublicationWorkerLauncher(TargetStorageResolver targetStorages,
			DatasetPublicationCredentialProjections projections) {
		return new DatasetPublicationWorkerLauncher(targetStorages, projections);
	}

}
