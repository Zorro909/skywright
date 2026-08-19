package de.zorro909.skywright.backend.targetstorage;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.http.RequestCorrelationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

final class TargetStorageProblemHandlerTest {

	private final TargetStorageProblemHandler handler = new TargetStorageProblemHandler();

	@Test
	void invalidDomainInputReturnsASafeStableClientProblem() {
		var request = request();

		var response = this.handler.handleInvalidRequest(new IllegalArgumentException("secret endpoint detail"),
				request);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody().getErrorCode()).isEqualTo("SKYWRIGHT_TARGET_STORAGE_INVALID_REQUEST");
		assertThat(response.getBody().getDetail()).doesNotContain("secret endpoint detail");
	}

	@Test
	void failedQualificationReturnsTheDeclaredStableUnprocessableProblem() {
		var response = this.handler
			.handle(new TargetStorageQualificationFailedException(CapabilityAvailability.INCOMPATIBLE), request());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
		assertThat(response.getBody().getErrorCode()).isEqualTo("SKYWRIGHT_TARGET_STORAGE_QUALIFICATION_FAILED");
	}

	private static MockHttpServletRequest request() {
		var request = new MockHttpServletRequest();
		request.setRequestURI("/api/v1/target-storages/00000000-0000-0000-0000-000000000001/qualification");
		request.setAttribute(RequestCorrelationFilter.REQUEST_ATTRIBUTE, "request-target-storage");
		return request;
	}

}
