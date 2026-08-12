package de.zorro909.skywright.backend.configuration;

import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.validation.FieldError;

/** Reports owned configuration failures without rendering supplied property values. */
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class SafeConfigurationPropertiesFailureAnalyzer
		extends AbstractFailureAnalyzer<ConfigurationPropertiesBindException> {

	@Override
	protected FailureAnalysis analyze(Throwable rootFailure, ConfigurationPropertiesBindException failure) {
		var description = validationDescription(failure);
		return new FailureAnalysis(description,
				"Correct the named Skywright deployment setting; supplied values are omitted from this diagnostic.",
				new IllegalStateException("Invalid Skywright deployment configuration"));
	}

	private String validationDescription(ConfigurationPropertiesBindException failure) {
		var unbound = findCause(failure, UnboundConfigurationPropertiesException.class);
		if (unbound != null) {
			var propertyNames = unbound.getUnboundProperties()
				.stream()
				.map(property -> property.getName().toString())
				.sorted()
				.collect(Collectors.joining(", "));
			return "Unknown Skywright deployment setting: " + propertyNames;
		}

		var validation = findCause(failure, BindValidationException.class);
		if (validation != null) {
			var errors = validation.getValidationErrors();
			var details = errors.getAllErrors().stream().map(error -> {
				var propertyName = errors.getName().toString();
				if (error instanceof FieldError fieldError) {
					propertyName += "." + fieldError.getField();
				}
				return "Property: " + propertyName + "\nReason: " + error.getDefaultMessage();
			}).collect(Collectors.joining("\n\n"));
			return "Invalid Skywright deployment configuration:\n\n" + details;
		}

		var bind = findCause(failure, BindException.class);
		var propertyName = bind == null ? failure.getAnnotation().prefix() : bind.getName().toString();
		return "Skywright deployment setting could not be bound: " + propertyName;
	}

}
