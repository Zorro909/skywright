package de.zorro909.skywright.backend.targetstorage;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TargetStorageConfigurationBeans {

	TargetStorageConfigurationBeans() {
	}

	@Bean
	TargetStorageRegistry targetStorageRegistry(TargetStorageRepository repository) {
		return new TargetStorageRegistry(repository);
	}

	@Bean
	@ConditionalOnMissingBean(value = { TargetStorageBindingReadiness.class })
	TargetStorageBindingReadiness targetStorageBindingReadiness() {
		return new MissingTargetStorageBindingReadiness();
	}

}
