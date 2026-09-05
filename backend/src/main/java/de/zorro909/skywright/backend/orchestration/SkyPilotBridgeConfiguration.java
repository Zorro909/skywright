package de.zorro909.skywright.backend.orchestration;

import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SkyPilotBridgeConfiguration {

	@Bean(destroyMethod = "close")
	GraalPySkyPilotClient skyPilotClient(SkyPilotBridgeProperties properties,
			org.springframework.beans.factory.ObjectProvider<de.zorro909.skywright.backend.credential.VaultBindings> vault,
			@org.springframework.beans.factory.annotation.Value("${skywright.credentials.skypilot-binding:}") String binding) {
		var client = new GraalPySkyPilotClient(properties.externalDirectory(), properties.apiServerEndpoint());
		if (vault.getIfAvailable() != null) {
			if (binding.isBlank()) {
				client.authorization(new de.zorro909.skywright.backend.credential.BackendSkyPilotAuthorization(
						vault.getObject(), new java.util.UUID(0, 0), properties.apiServerEndpoint().toString()));
			}
			else {
				client.authorization(
						new de.zorro909.skywright.backend.credential.BackendSkyPilotAuthorization(vault.getObject(),
								java.util.UUID.fromString(binding), properties.apiServerEndpoint().toString()));
			}
		}
		return client;
	}

	@Bean(destroyMethod = "close")
	Orchestrator skyPilotOrchestrator(SkyPilotBridgeProperties properties, GraalPySkyPilotClient client) {
		var settings = new SkyPilotBridgeSettings(properties.controlQueueCapacity(), properties.heldQueueCapacity(),
				properties.shutdownGrace());
		return new SkyPilotOrchestrator(client, settings);
	}

	@Bean
	AvailabilityProbe skyPilotAvailabilityProbe(Orchestrator orchestrator) {
		return new AvailabilityProbe(orchestrator);
	}

	static final class AvailabilityProbe {

		private final Orchestrator orchestrator;

		AvailabilityProbe(Orchestrator orchestrator) {
			this.orchestrator = orchestrator;
		}

		@Scheduled(fixedDelayString = "${skywright.skypilot.bridge.availability-probe-interval:30s}",
				timeUnit = TimeUnit.MILLISECONDS)
		void refresh() {
			this.orchestrator.refreshAvailability();
		}

	}

}
