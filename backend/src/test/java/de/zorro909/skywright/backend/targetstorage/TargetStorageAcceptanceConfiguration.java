package de.zorro909.skywright.backend.targetstorage;

import de.zorro909.skywright.backend.runstore.RunStoreS3CapabilityFloor;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Packaged-browser fixture enabled only for the acceptance deployment. */
@Configuration(proxyBeanMethods = false)
@Profile("target-storage-acceptance")
class TargetStorageAcceptanceConfiguration {

	TargetStorageAcceptanceConfiguration(@Value("${skywright.deployment.environment}") String deploymentEnvironment) {
		if (!"acceptance".equals(deploymentEnvironment)) {
			throw new IllegalStateException("The Target Storage acceptance fixture requires the acceptance deployment");
		}
	}

	@Bean
	@Primary
	TargetStorageBindingReadiness acceptanceTargetStorageBindingReadiness() {
		return (bindingId, bindingRevision, consumingRole) -> BindingReadiness.READY;
	}

	@Bean
	@Primary
	TargetStorageQualificationProbe acceptanceTargetStorageQualificationProbe() {
		return request -> {
			Instant observed = Instant.now();
			boolean bindingsReady = request.bindings()
				.stream()
				.filter(binding -> binding.readiness() == BindingReadiness.READY)
				.map(TargetStorageBinding::role)
				.collect(java.util.stream.Collectors.toSet())
				.containsAll(request.purpose().requiredRoles());
			return new TargetStorageAssessment(UUID.randomUUID(), request.configurationRevision(), observed,
					Instant.now(),
					bindingsReady ? CapabilityAvailability.AVAILABLE : CapabilityAvailability.TRANSIENTLY_UNAVAILABLE,
					request.bindings().stream().map(TargetStorageBindingRevision::from).toList(),
					RunStoreS3CapabilityFloor.requiredCapabilities()
						.stream()
						.map(capability -> bindingsReady ? TargetStorageCapabilityResult.success(capability)
								: TargetStorageCapabilityResult.failure(capability, "credential-binding-unavailable",
										"A ready backend Credential Binding is required to exercise this capability",
										Map.of()))
						.toList());
		};
	}

}
