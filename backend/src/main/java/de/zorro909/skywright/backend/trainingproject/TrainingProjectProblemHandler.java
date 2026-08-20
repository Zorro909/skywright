package de.zorro909.skywright.backend.trainingproject;

import de.zorro909.skywright.backend.boundary.generated.model.Problem;
import de.zorro909.skywright.backend.http.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TrainingProjectHttpAdapter.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class TrainingProjectProblemHandler {

	@ExceptionHandler(TrainingProjectException.class)
	ResponseEntity<Problem> handle(TrainingProjectException failure, HttpServletRequest request) {
		HttpStatus status = status(failure.code());
		return problem(status, failure.getMessage(), "SKYWRIGHT_" + failure.code(), request);
	}

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ResponseEntity<Problem> handleOptimisticConflict(ObjectOptimisticLockingFailureException failure,
			HttpServletRequest request) {
		return problem(HttpStatus.CONFLICT, "The Training Project changed; reload it and retry.",
				"SKYWRIGHT_TRAINING_PROJECT_REVISION_CONFLICT", request);
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<Problem> handleConstraintConflict(DataIntegrityViolationException failure,
			HttpServletRequest request) {
		String code;
		if (contains(failure, "training_project_display_name_key")) {
			code = "TRAINING_PROJECT_NAME_CONFLICT";
		}
		else if (contains(failure, "registry_rebinding_one_active_key")) {
			code = "REGISTRY_REBINDING_CONFLICT";
		}
		else {
			code = "TRAINING_PROJECT_REPOSITORY_CONFLICT";
		}
		return problem(HttpStatus.CONFLICT, "The Training Project conflicts with current configuration.",
				"SKYWRIGHT_" + code, request);
	}

	private static HttpStatus status(String code) {
		if (code.endsWith("NOT_FOUND")) {
			return HttpStatus.NOT_FOUND;
		}
		if (code.endsWith("CONFLICT")) {
			return HttpStatus.CONFLICT;
		}
		return HttpStatus.UNPROCESSABLE_ENTITY;
	}

	private static ResponseEntity<Problem> problem(HttpStatus status, String detail, String code,
			HttpServletRequest request) {
		Problem problem = new Problem("about:blank", status.getReasonPhrase(), status.value(), detail,
				request.getRequestURI(), code, RequestCorrelationFilter.correlationIdFrom(request), List.of(), null,
				false);
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
	}

	private static boolean contains(Throwable failure, String text) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current.getMessage() != null && current.getMessage().contains(text)) {
				return true;
			}
		}
		return false;
	}

}
