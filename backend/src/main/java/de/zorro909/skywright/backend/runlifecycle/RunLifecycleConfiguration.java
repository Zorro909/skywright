package de.zorro909.skywright.backend.runlifecycle;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
class RunLifecycleConfiguration {

	@Bean
	ThreadPoolTaskScheduler runRetentionScheduler() {
		var scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("run-retention-");
		scheduler.setDaemon(true);
		scheduler.setWaitForTasksToCompleteOnShutdown(false);
		return scheduler;
	}

}
