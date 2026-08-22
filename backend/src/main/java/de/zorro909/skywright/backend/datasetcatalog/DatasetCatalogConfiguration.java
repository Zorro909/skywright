package de.zorro909.skywright.backend.datasetcatalog;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DatasetCatalogConfiguration {

	@Bean
	DatasetCatalog datasetCatalog(DatasetCatalogRepository repository) {
		return new DatasetCatalog(repository, Clock.systemUTC());
	}

}
