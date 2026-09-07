package de.zorro909.skywright.backend.runsubmission;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
class RunCommandConfiguration {

	@Bean(destroyMethod = "shutdownNow")
	ThreadPoolExecutor runCommandExecutor() {
		return new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16),
				Thread.ofPlatform().daemon().name("run-command-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
	}

	@Bean
	ThreadPoolTaskScheduler runCommandScheduler() {
		var scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setDaemon(true);
		scheduler.setThreadNamePrefix("run-command-scheduler-");
		scheduler.setWaitForTasksToCompleteOnShutdown(false);
		return scheduler;
	}

}
