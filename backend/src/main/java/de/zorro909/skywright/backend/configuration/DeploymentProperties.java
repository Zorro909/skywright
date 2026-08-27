package de.zorro909.skywright.backend.configuration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.Currency;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Skywright-owned settings that identify this backend deployment. */
@ConfigurationProperties(prefix = "skywright.deployment", ignoreUnknownFields = false)
@Validated
public record DeploymentProperties(
		/** Stable, non-secret name for the environment served by this process. */
		@NotBlank(message = "must be configured") @Pattern(regexp = "[a-z][a-z0-9-]{0,31}",
				message = "must be a lowercase deployment identifier") String environment,
		/** ISO 4217 currency used for quotes and run cost estimates. */
		@NotNull(message = "must be configured") Currency reportingCurrency) {

	public int reportingCurrencyMinorUnit() {
		return this.reportingCurrency.getDefaultFractionDigits();
	}

	@AssertTrue(message = "reporting currency must define a standard minor unit")
	public boolean isReportingCurrencyMinorUnitDefined() {
		return this.reportingCurrency == null || reportingCurrencyMinorUnit() >= 0;
	}

}
