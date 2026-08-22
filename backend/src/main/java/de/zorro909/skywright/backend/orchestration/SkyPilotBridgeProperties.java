package de.zorro909.skywright.backend.orchestration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "skywright.skypilot.bridge", ignoreUnknownFields = false)
@Validated
public record SkyPilotBridgeProperties(@DefaultValue("8") @Min(1) int controlQueueCapacity,
		@DefaultValue("4") @Min(1) int heldQueueCapacity, @DefaultValue("20s") @NotNull Duration shutdownGrace,
		@DefaultValue("30s") @NotNull Duration availabilityProbeInterval,
		@DefaultValue("graalpy-resources") @NotNull Path externalDirectory,
		@DefaultValue("http://127.0.0.1:46580") @NotNull URI apiServerEndpoint) {
	public SkyPilotBridgeProperties {
		if (apiServerEndpoint == null || apiServerEndpoint.getHost() == null
				|| !("http".equals(apiServerEndpoint.getScheme()) || "https".equals(apiServerEndpoint.getScheme()))) {
			throw new IllegalArgumentException("api server endpoint must be an absolute HTTP(S) URI");
		}
	}

}
