package de.zorro909.skywright.backend.pricing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity(name = "CurrencyConversionEntity")
@Table(name = "currency_conversion")
class CurrencyConversionEntity {

	@Id
	UUID id;

	@Column(name = "price_source_id", nullable = false)
	UUID sourceId;

	@Column(name = "native_currency", nullable = false, length = 3)
	String nativeCurrency;

	@Column(name = "reporting_currency", nullable = false, length = 3)
	String reportingCurrency;

	@Column(nullable = false, columnDefinition = "numeric")
	BigDecimal rate;

	@Column(nullable = false, columnDefinition = "jsonb")
	@JdbcTypeCode(SqlTypes.JSON)
	Map<String, Object> provenance;

	@Column(name = "observed_at", nullable = false)
	Instant observedAt;

	@Column(name = "effective_from", nullable = false)
	Instant effectiveFrom;

	@Column(name = "effective_until", nullable = false)
	Instant effectiveUntil;

	@Version
	@Column(name = "persistence_version", nullable = false)
	long persistenceVersion;

	protected CurrencyConversionEntity() {
	}

	CurrencyConversionEntity(UUID id, UUID sourceId, CurrencyConversionValue value) {
		this.id = id;
		this.sourceId = sourceId;
		replace(value);
	}

	void replace(CurrencyConversionValue value) {
		this.nativeCurrency = value.nativeCurrency();
		this.reportingCurrency = value.reportingCurrency();
		this.rate = value.rate();
		this.provenance = value.provenance();
		this.observedAt = value.observedAt();
		this.effectiveFrom = value.effectiveFrom();
		this.effectiveUntil = value.effectiveUntil();
	}

	CurrencyConversionView view() {
		return new CurrencyConversionView(this.id, this.nativeCurrency, this.reportingCurrency, this.rate,
				this.provenance, this.observedAt, this.effectiveFrom, this.effectiveUntil);
	}

}

record CurrencyConversionValue(String nativeCurrency, String reportingCurrency, BigDecimal rate,
		Map<String, Object> provenance, Instant observedAt, Instant effectiveFrom, Instant effectiveUntil) {
}

record CurrencyConversionView(UUID id, String nativeCurrency, String reportingCurrency, BigDecimal rate,
		Map<String, Object> provenance, Instant observedAt, Instant effectiveFrom, Instant effectiveUntil) {
}

record CurrencyConversionScheduleView(UUID sourceId, long scheduleRevision,
		java.util.List<CurrencyConversionView> entries) {
}
