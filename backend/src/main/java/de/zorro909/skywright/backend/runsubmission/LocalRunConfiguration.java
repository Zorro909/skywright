package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.orchestration.Orchestrator;
import de.zorro909.skywright.backend.orchestration.RunJobAdapter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@org.springframework.boot.context.properties.EnableConfigurationProperties(LocalRunTargetSettings.class)
@Configuration(proxyBeanMethods = false)
class LocalRunConfiguration {

	@Bean
	RunJobAdapter runJobAdapter(Orchestrator orchestrator, RunAcceptanceStore store) {
		return new RunJobAdapter(orchestrator, store, store, java.time.Clock.systemUTC());
	}

}
