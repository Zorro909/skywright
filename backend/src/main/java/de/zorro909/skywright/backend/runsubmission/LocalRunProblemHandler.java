package de.zorro909.skywright.backend.runsubmission;

import de.zorro909.skywright.backend.boundary.generated.model.Problem;
import de.zorro909.skywright.backend.boundary.generated.model.FieldViolation;
import de.zorro909.skywright.backend.http.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LocalRunHttpAdapter.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class LocalRunProblemHandler {

	@ExceptionHandler(RunSubmissionException.class)
	ResponseEntity<Problem> handle(RunSubmissionException failure, HttpServletRequest request) {
		var status = org.springframework.http.HttpStatus.valueOf(failure.status());
		return ResponseEntity.status(status)
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(new Problem("about:blank", status.getReasonPhrase(), status.value(), failure.code(),
					request.getRequestURI(), "SKYWRIGHT_" + failure.code(),
					RequestCorrelationFilter.correlationIdFrom(request),
					failure.failures()
						.stream()
						.map(item -> new FieldViolation(item.pointer(), item.code(), item.keyword()))
						.toList(),
					null, failure.status() == 503));
	}

	@ExceptionHandler(de.zorro909.skywright.backend.datasetcatalog.DatasetCatalogException.class)
	ResponseEntity<Problem> dataset(de.zorro909.skywright.backend.datasetcatalog.DatasetCatalogException failure,
			HttpServletRequest request) {
		return handle(new RunSubmissionException(failure.errorCode(),
				failure.errorCode().endsWith("_UNAVAILABLE") ? 503 : 422), request);
	}

	@ExceptionHandler(de.zorro909.skywright.backend.trainingproject.TrainingProjectException.class)
	ResponseEntity<Problem> project(de.zorro909.skywright.backend.trainingproject.TrainingProjectException failure,
			HttpServletRequest request) {
		return handle(new RunSubmissionException(failure.code(), failure.code().endsWith("_UNAVAILABLE") ? 503 : 422),
				request);
	}

	@ExceptionHandler(de.zorro909.skywright.backend.targetstorage.TargetStorageException.class)
	ResponseEntity<Problem> storage(de.zorro909.skywright.backend.targetstorage.TargetStorageException failure,
			HttpServletRequest request) {
		return handle(new RunSubmissionException(failure.code(), 503), request);
	}

	@ExceptionHandler({ IllegalStateException.class, org.springframework.dao.DataAccessException.class })
	ResponseEntity<Problem> unavailable(RuntimeException failure, HttpServletRequest request) {
		return handle(new RunSubmissionException("RUN_ADMISSION_UNAVAILABLE", 503), request);
	}

}
