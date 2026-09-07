package de.zorro909.skywright.backend.runlog;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
class RunLogConfiguration {

	@Bean(destroyMethod = "shutdownNow")
	ThreadPoolExecutor runLogExecutor() {
		return new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
				Thread.ofPlatform().daemon().name("run-log-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
	}

	@Bean
	ThreadPoolTaskScheduler runLogScheduler() {
		var scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setDaemon(true);
		scheduler.setThreadNamePrefix("run-log-scheduler-");
		scheduler.setWaitForTasksToCompleteOnShutdown(false);
		return scheduler;
	}

}
