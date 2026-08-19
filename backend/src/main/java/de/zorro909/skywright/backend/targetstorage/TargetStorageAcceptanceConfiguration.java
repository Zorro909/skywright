package de.zorro909.skywright.backend.targetstorage;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

/**
 * Enables the packaged browser seam without introducing a production credential
 * implementation.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("'${skywright.deployment.environment:}' == 'acceptance' && "
		+ "'${skywright.acceptance.target-storage.enabled:false}' == 'true'")
class TargetStorageAcceptanceConfiguration {

	@Bean
	@Primary
	TargetStorageBindingReadiness acceptanceTargetStorageBindingReadiness() {
		return (bindingId, bindingRevision, consumingRole) -> BindingReadiness.READY;
	}

	@Bean
	TargetStorageCredentialAccess acceptanceTargetStorageCredentialAccess() {
		var provider = DefaultCredentialsProvider.create();
		return (bindingId, bindingRevision, consumingRole) -> Optional.of(provider);
	}

}
