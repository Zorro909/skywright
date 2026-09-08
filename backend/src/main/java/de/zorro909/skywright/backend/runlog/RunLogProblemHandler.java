package de.zorro909.skywright.backend.runlog;

import de.zorro909.skywright.backend.boundary.generated.model.Problem;
import de.zorro909.skywright.backend.http.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = RunLogHttpAdapter.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class RunLogProblemHandler {

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<Problem> invalid(ConstraintViolationException failure, HttpServletRequest request) {
		return problem(400, request);
	}

	@ExceptionHandler(ResponseStatusException.class)
	ResponseEntity<Problem> unavailable(ResponseStatusException failure, HttpServletRequest request) {
		return problem(failure.getStatusCode().value(), request);
	}

	private static ResponseEntity<Problem> problem(int status, HttpServletRequest request) {
		String code = status == 400 ? "SKYWRIGHT_ARCHIVE_REQUEST_INVALID" : "SKYWRIGHT_ARCHIVE_READ_UNAVAILABLE";
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(new Problem("about:blank", HttpStatus.valueOf(status).getReasonPhrase(), status,
					status == 400 ? "The archive cursor or stream is invalid."
							: "The archive read is unavailable; retry later.",
					request.getRequestURI(), code, RequestCorrelationFilter.correlationIdFrom(request), List.of(), null,
					status == 429 || status == 503));
	}

}
