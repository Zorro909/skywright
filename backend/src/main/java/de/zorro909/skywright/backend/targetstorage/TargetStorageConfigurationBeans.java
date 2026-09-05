package de.zorro909.skywright.backend.targetstorage;

import de.zorro909.skywright.backend.credential.VaultBindings;
import de.zorro909.skywright.backend.credential.VaultRoleAccess;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class TargetStorageConfigurationBeans {

	TargetStorageConfigurationBeans() {
	}

	@Bean
	TargetStorageRegistry targetStorageRegistry(TargetStorageRepository repository,
			TargetStorageBindingReadiness readiness) {
		return new TargetStorageRegistry(repository, readiness);
	}

	@Bean
	@ConditionalOnMissingBean(value = { TargetStorageBindingReadiness.class })
	TargetStorageBindingReadiness targetStorageBindingReadiness(ObjectProvider<VaultBindings> vault) {
		var bindings = vault.getIfAvailable();
		return bindings == null ? new MissingTargetStorageBindingReadiness() : new VaultRoleAccess(bindings)::readiness;
	}

	@Bean
	@ConditionalOnProperty(name = "skywright.credentials.vault.bindings-file")
	TargetStorageCredentialAccess vaultStorageCredentialAccess(VaultBindings bindings) {
		return new VaultRoleAccess(bindings)::credentials;
	}

	@Bean
	@org.springframework.context.annotation.Primary
	@ConditionalOnProperty(name = "skywright.credentials.vault.bindings-file")
	@ConditionalOnMissingBean(S3TargetStorageQualificationProbe.class)
	S3TargetStorageQualificationProbe vaultStorageQualificationProbe(TargetStorageCredentialAccess credentials) {
		return new S3TargetStorageQualificationProbe(credentials);
	}

}
