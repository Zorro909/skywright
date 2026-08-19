package de.zorro909.skywright.backend.targetstorage;

import de.zorro909.skywright.backend.boundary.generated.model.Problem;
import de.zorro909.skywright.backend.http.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
final class TargetStorageProblemHandler {

	TargetStorageProblemHandler() {
	}

	@ExceptionHandler(value = { TargetStorageException.class })
	ResponseEntity<Problem> handle(TargetStorageException failure, HttpServletRequest request) {
		HttpStatus status = TargetStorageProblemHandler.status(failure);
		Problem problem = new Problem("about:blank", status.getReasonPhrase(), status.value(),
				TargetStorageProblemHandler.safeDetail(failure), request.getRequestURI(), "SKYWRIGHT_" + failure.code(),
				RequestCorrelationFilter.correlationIdFrom((HttpServletRequest) request), List.of(), null,
				status == HttpStatus.SERVICE_UNAVAILABLE);
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
	}

	@ExceptionHandler(value = { ObjectOptimisticLockingFailureException.class })
	ResponseEntity<Problem> handleConcurrentUpdate(ObjectOptimisticLockingFailureException failure,
			HttpServletRequest request) {
		Problem problem = new Problem("about:blank", HttpStatus.CONFLICT.getReasonPhrase(), HttpStatus.CONFLICT.value(),
				"The Target Storage changed concurrently; reload it and retry.", request.getRequestURI(),
				"SKYWRIGHT_TARGET_STORAGE_REVISION_CONFLICT",
				RequestCorrelationFilter.correlationIdFrom((HttpServletRequest) request), List.of(), null, false);
		return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
	}

	@ExceptionHandler(value = { DataIntegrityViolationException.class })
	ResponseEntity<Problem> handleResourceConflict(DataIntegrityViolationException failure,
			HttpServletRequest request) {
		boolean duplicateResource = TargetStorageProblemHandler.causeContains(failure,
				"uk_target_storage_resource_key");
		Problem problem = new Problem("about:blank", HttpStatus.CONFLICT.getReasonPhrase(), HttpStatus.CONFLICT.value(),
				duplicateResource ? "The endpoint and bucket are already registered."
						: "The Target Storage update conflicts with durable registry state.",
				request.getRequestURI(),
				duplicateResource ? "SKYWRIGHT_TARGET_STORAGE_RESOURCE_CONFLICT"
						: "SKYWRIGHT_TARGET_STORAGE_PERSISTENCE_CONFLICT",
				RequestCorrelationFilter.correlationIdFrom(request), List.of(), null, false);
		return ResponseEntity.status(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
	}

	private static boolean causeContains(Throwable failure, String text) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current.getMessage() != null && current.getMessage().contains(text)) {
				return true;
			}
		}
		return false;
	}

	private static HttpStatus status(TargetStorageException failure) {
		if (failure instanceof TargetStorageNotFoundException) {
			return HttpStatus.NOT_FOUND;
		}
		if (failure instanceof TargetStorageConflictException || failure instanceof TargetStorageReferencedException) {
			return HttpStatus.CONFLICT;
		}
		return HttpStatus.UNPROCESSABLE_ENTITY;
	}

	private static String safeDetail(TargetStorageException failure) {
		int separator = failure.getMessage().indexOf(": ");
		return separator < 0 ? "The Target Storage operation could not be completed."
				: failure.getMessage().substring(separator + 2);
	}

}
