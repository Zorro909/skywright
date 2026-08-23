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
	Orchestrator skyPilotOrchestrator(SkyPilotBridgeProperties properties) {
		var settings = new SkyPilotBridgeSettings(properties.controlQueueCapacity(), properties.heldQueueCapacity(),
				properties.shutdownGrace());
		return new SkyPilotOrchestrator(
				new GraalPySkyPilotClient(properties.externalDirectory(), properties.apiServerEndpoint()), settings);
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
