package de.zorro909.skywright.backend.targetstorage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
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

}
