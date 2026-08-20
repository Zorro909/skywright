package de.zorro909.skywright.backend.targetstorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

final class TargetStorageProblemHandlerTest {

	@Test
	void ineligibleStorageUsesTheStableDomainProblemCode() {
		var request = new MockHttpServletRequest("PUT", "/api/target-storages/storage-id/activation");
		var failure = new TargetStorageIneligibleException("TARGET_STORAGE_NOT_QUALIFIED",
				"Target Storage cannot activate without a successful qualification");

		var response = new TargetStorageProblemHandler().handle(failure, request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getErrorCode()).isEqualTo("SKYWRIGHT_TARGET_STORAGE_NOT_QUALIFIED");
	}

	@Test
	void unrelatedIntegrityFailureIsNotMisreportedAsAResourceClaimConflict() {
		var request = new MockHttpServletRequest("POST", "/api/v1/target-storages/id/qualification");
		var failure = new DataIntegrityViolationException("assessment conflict",
				new IllegalStateException("uq_target_storage_assessment_id"));

		var response = new TargetStorageProblemHandler().handleResourceConflict(failure, request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().getErrorCode()).isEqualTo("SKYWRIGHT_HTTP_INTERNAL_ERROR");
	}

	@Test
	void malformedEndpointFailureNeverCarriesTheSubmittedValueIntoLogs() {
		assertThatThrownBy(
				() -> TargetStorageHttpAdapter.parseEndpoint("http://operator:do-not-log@storage.example/%ZZ"))
			.isInstanceOf(TargetStorageValidationException.class)
			.hasMessageContaining("endpoint must be a valid URI")
			.hasMessageNotContaining("do-not-log")
			.hasMessageNotContaining("%ZZ");
	}

}
