package de.zorro909.skywright.backend.datasetcatalog;

import de.zorro909.skywright.backend.targetstorage.TargetStorageRegistry;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.EnableScheduling;
import de.zorro909.skywright.backend.targetstorage.TargetStorageResolver;
import org.springframework.beans.factory.ObjectProvider;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class DatasetCatalogConfiguration {

	@Bean
	DatasetCatalog datasetCatalog(DatasetCatalogRepository repository, TargetStorageRegistry targetStorages,
			ObjectProvider<DatasetCopyStorage> copyStorage) {
		DatasetCopyVerifier metadataOnlyVerifier = (definition, manifest, copy) -> {
			if (!definition.manifestIdentity().equals(copy.currentGeneration().manifestIdentity())
					|| !definition.contentFingerprint().equals(copy.currentGeneration().contentFingerprint())) {
				throw new DatasetCatalogConflictException("DATASET_COPY_MANIFEST_MISMATCH",
						"Dataset Copy does not match the Dataset Definition manifest");
			}
		};
		DatasetCopyStorage availableStorage = copyStorage.getIfAvailable();
		DatasetCopyVerifier verifier = availableStorage == null ? metadataOnlyVerifier : availableStorage;
		return new DatasetCatalog(repository, Clock.systemUTC(), verifier, targetStorages::eligibleDataset);
	}

	@Bean
	@ConditionalOnBean(TargetStorageResolver.class)
	DatasetCopyStorage datasetCopyStorage(TargetStorageResolver targetStorages) {
		return new S3DatasetCopyStorage(targetStorages);
	}

	@Bean
	@ConditionalOnBean(DatasetCopyStorage.class)
	DatasetCopyMaintenanceWorker datasetCopyMaintenanceWorker(DatasetCatalog catalog, DatasetCopyStorage storage) {
		return new DatasetCopyMaintenanceWorker(catalog, storage);
	}

}
