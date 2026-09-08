package de.zorro909.skywright.backend.datasetcatalog;

import de.zorro909.skywright.backend.targetstorage.TargetStorageRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.EnableScheduling;
import de.zorro909.skywright.backend.targetstorage.TargetStorageResolver;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class DatasetCatalogConfiguration {

	@Bean
	DatasetCatalog datasetCatalog(DatasetCatalogRepository repository, TargetStorageRegistry targetStorages) {
		return new DatasetCatalog(repository, Clock.systemUTC(), targetStorages::eligibleDataset);
	}

	@Bean
	@ConditionalOnBean(TargetStorageResolver.class)
	DatasetCopyWorkerLauncher datasetCopyWorkerLauncher(TargetStorageResolver targetStorages,
			DatasetCopyWorkerProjections projections,
			@org.springframework.beans.factory.annotation.Value("${skywright.dataset-catalog.worker-timeout:PT1H}") java.time.Duration timeout) {
		return new DatasetCopyWorkerLauncher(targetStorages, projections, timeout);
	}

	@Bean
	@ConditionalOnBean(DatasetCopyWorkerLauncher.class)
	DatasetCopyStorage datasetCopyStorage(DatasetCopyWorkerLauncher workers) {
		return new S3DatasetCopyStorage(workers);
	}

	@Bean
	@ConditionalOnBean(DatasetCopyStorage.class)
	DatasetCopyMaintenanceWorker datasetCopyMaintenanceWorker(DatasetCatalog catalog, DatasetCopyStorage storage) {
		return new DatasetCopyMaintenanceWorker(catalog, storage);
	}

}
