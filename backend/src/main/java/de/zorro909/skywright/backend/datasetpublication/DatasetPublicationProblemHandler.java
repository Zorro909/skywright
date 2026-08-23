package de.zorro909.skywright.backend.datasetpublication;

import de.zorro909.skywright.backend.boundary.generated.model.Problem;
import de.zorro909.skywright.backend.http.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = DatasetPublicationHttpAdapter.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class DatasetPublicationProblemHandler {

	@ExceptionHandler(DatasetPublicationException.class)
	ResponseEntity<Problem> handle(DatasetPublicationException failure, HttpServletRequest request) {
		HttpStatus status = failure.errorCode().endsWith("_NOT_FOUND") ? HttpStatus.NOT_FOUND : failure.retryable()
				? HttpStatus.SERVICE_UNAVAILABLE
				: failure.errorCode().endsWith("_INVALID") ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.CONFLICT;
		Problem body = new Problem("about:blank", status.getReasonPhrase(), status.value(), publicDetail(failure),
				request.getRequestURI(), "SKYWRIGHT_" + failure.errorCode(),
				RequestCorrelationFilter.correlationIdFrom(request), List.of(),
				failure.retryable() ? "Dataset Target Storage" : null, failure.retryable());
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
	}

	private static String publicDetail(DatasetPublicationException failure) {
		if (failure.retryable()) {
			return "Dataset publication storage is temporarily unavailable; retry the operation.";
		}
		if (failure.errorCode().endsWith("_NOT_FOUND")) {
			return "The requested Dataset Publication resource does not exist.";
		}
		if (failure.errorCode().endsWith("_INVALID")) {
			return "The Dataset Publication request is invalid.";
		}
		return "The Dataset Publication conflicts with its staged or catalog state.";
	}

}
