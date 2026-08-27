package de.zorro909.skywright.backend.configuration;

import de.zorro909.skywright.backend.rundefinition.ReportingCurrencyReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DeploymentConfiguration {

	@Bean
	ReportingCurrencyReader reportingCurrencyReader(DeploymentProperties properties) {
		return () -> properties.reportingCurrency().getCurrencyCode();
	}

}
