package de.zorro909.skywright.backend.targetstorage;

import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class TargetStorageWebConfiguration implements WebMvcConfigurer {

	@Override
	public void addFormatters(FormatterRegistry registry) {
		registry.addConverter(String.class, de.zorro909.skywright.backend.boundary.generated.model.TargetClass.class,
				de.zorro909.skywright.backend.boundary.generated.model.TargetClass::fromValue);
	}

}
