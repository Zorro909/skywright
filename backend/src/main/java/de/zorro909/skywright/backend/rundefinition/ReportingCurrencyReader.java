package de.zorro909.skywright.backend.rundefinition;

/** Narrow read port for the deployment's resolved Reporting Currency. */
@FunctionalInterface
public interface ReportingCurrencyReader {

	String reportingCurrency();

}
