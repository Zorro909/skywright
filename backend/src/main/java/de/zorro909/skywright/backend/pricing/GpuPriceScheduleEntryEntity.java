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

@Entity(name = "GpuPriceScheduleEntryEntity")
@Table(name = "gpu_price_schedule_entry")
class GpuPriceScheduleEntryEntity {

	@Id
	UUID id;

	@Column(nullable = false)
	long revision;

	@Column(name = "price_source_id", nullable = false)
	UUID sourceId;

	@Column(name = "source_revision", nullable = false)
	long sourceRevision;

	@Column(name = "eligible_gpu_offering_id", nullable = false)
	UUID offeringId;

	@Column(name = "native_currency", nullable = false)
	String nativeCurrency;

	@Column(name = "native_unit", nullable = false)
	String nativeUnit;

	@Column(name = "exact_value", nullable = false)
	BigDecimal value;

	@Column(name = "minimum_quantity", nullable = false)
	BigDecimal minimumQuantity;

	@Column(name = "billing_quantum", nullable = false)
	BigDecimal billingQuantum;

	@Column(name = "provenance_json", nullable = false, length = 100000)
	String provenanceJson;

	@Column(name = "observed_at", nullable = false)
	Instant observedAt;

	@Column(name = "effective_from", nullable = false)
	Instant effectiveFrom;

	@Column(name = "effective_until", nullable = false)
	Instant effectiveUntil;

	@Version
	@Column(name = "persistence_version", nullable = false)
	long persistenceVersion;

	protected GpuPriceScheduleEntryEntity() {
	}

	static GpuPriceScheduleEntryEntity create(UUID id, UUID sourceId, GpuPriceScheduleEntryInput input,
			String provenanceJson) {
		GpuPriceScheduleEntryEntity entry = new GpuPriceScheduleEntryEntity();
		entry.id = id;
		entry.revision = 1;
		entry.sourceId = sourceId;
		entry.replace(input, provenanceJson);
		return entry;
	}

	void replace(GpuPriceScheduleEntryInput input, String provenanceJson) {
		this.sourceRevision = input.sourceRevision();
		this.offeringId = input.offeringId();
		this.nativeCurrency = input.nativeCurrency();
		this.nativeUnit = input.nativeUnit();
		this.value = input.value();
		this.minimumQuantity = input.minimumQuantity();
		this.billingQuantum = input.billingQuantum();
		this.provenanceJson = provenanceJson;
		this.observedAt = input.observedAt();
		this.effectiveFrom = input.effectiveFrom();
		this.effectiveUntil = input.effectiveUntil();
	}

	GpuPriceScheduleEntryView view(Map<String, Object> provenance) {
		return new GpuPriceScheduleEntryView(this.id, this.revision, this.sourceId, this.sourceRevision,
				this.offeringId, this.nativeCurrency, this.nativeUnit, this.value, this.minimumQuantity,
				this.billingQuantum, provenance, this.observedAt, this.effectiveFrom, this.effectiveUntil);
	}

}

record GpuPriceScheduleEntryInput(long sourceRevision, UUID offeringId, String nativeCurrency, String nativeUnit,
		BigDecimal value, BigDecimal minimumQuantity, BigDecimal billingQuantum, Map<String, Object> provenance,
		Instant observedAt, Instant effectiveFrom, Instant effectiveUntil) {
}

record GpuPriceScheduleEntryView(UUID id, long revision, UUID sourceId, long sourceRevision, UUID offeringId,
		String nativeCurrency, String nativeUnit, BigDecimal value, BigDecimal minimumQuantity,
		BigDecimal billingQuantum, Map<String, Object> provenance, Instant observedAt, Instant effectiveFrom,
		Instant effectiveUntil) {
}
