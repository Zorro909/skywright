package de.zorro909.skywright.backend.availability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
final class ReadinessStateLogger implements ApplicationListener<AvailabilityChangeEvent<ReadinessState>> {

	private static final Logger LOGGER = LoggerFactory.getLogger(ReadinessStateLogger.class);

	@Override
	public void onApplicationEvent(AvailabilityChangeEvent<ReadinessState> event) {
		LOGGER.atInfo().addKeyValue("readinessState", event.getState().name()).log("Backend readiness changed");
	}

}
