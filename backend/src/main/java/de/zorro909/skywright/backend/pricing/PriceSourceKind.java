package de.zorro909.skywright.backend.pricing;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;

enum PriceSourceKind {

	OPERATOR_SCHEDULE("operator-schedule"), PROVIDER_API("provider-api"), SKYPILOT_CATALOG("skypilot-catalog");

	private final String wireValue;

	PriceSourceKind(String wireValue) {
		this.wireValue = wireValue;
	}

	String wireValue() {
		return this.wireValue;
	}

	static PriceSourceKind parse(String value) {
		return Arrays.stream(values())
			.filter(kind -> kind.wireValue.equals(value))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Price Source kind is not supported"));
	}

}

@Converter
class PriceSourceKindConverter implements AttributeConverter<PriceSourceKind, String> {

	@Override
	public String convertToDatabaseColumn(PriceSourceKind attribute) {
		return attribute == null ? null : attribute.wireValue();
	}

	@Override
	public PriceSourceKind convertToEntityAttribute(String value) {
		return value == null ? null : PriceSourceKind.parse(value);
	}

}
