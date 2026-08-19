package de.zorro909.skywright.backend.targetstorage;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;

@Configuration(proxyBeanMethods = false)
class TargetStorageConfigurationBeans {

	TargetStorageConfigurationBeans() {
	}

	@Bean
	TargetStorageRegistry targetStorageRegistry(TargetStorageRepository repository,
			TargetStorageBindingReadiness bindingReadiness, Optional<TargetStorageCredentialAccess> credentialAccess,
			TargetStorageReferenceCheck referenceCheck) {
		return new TargetStorageRegistry(repository, bindingReadiness, credentialAccess, referenceCheck);
	}

	@Bean
	@ConditionalOnMissingBean(value = { TargetStorageBindingReadiness.class })
	TargetStorageBindingReadiness targetStorageBindingReadiness() {
		return new MissingTargetStorageBindingReadiness();
	}

	@Bean
	@ConditionalOnMissingBean(value = { TargetStorageReferenceCheck.class })
	TargetStorageReferenceCheck targetStorageReferenceCheck() {
		return storageId -> false;
	}

	@Bean
	Converter<String, de.zorro909.skywright.backend.boundary.generated.model.TargetClass> targetClassConverter() {
		return de.zorro909.skywright.backend.boundary.generated.model.TargetClass::fromValue;
	}

}
