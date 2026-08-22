package de.zorro909.skywright.backend.datasetcatalog;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class DatasetCatalogWebConfiguration implements WebMvcConfigurer {

	@Override
	public void addFormatters(FormatterRegistry registry) {
		registry.addConverter(String.class,
				de.zorro909.skywright.backend.boundary.generated.model.DatasetCopyRole.class,
				de.zorro909.skywright.backend.boundary.generated.model.DatasetCopyRole::fromValue);
	}

}
