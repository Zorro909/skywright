package de.zorro909.skywright.backend.gpuoffering;

import de.zorro909.skywright.backend.boundary.generated.model.Problem;
import de.zorro909.skywright.backend.http.RequestCorrelationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = EligibleGpuOfferingHttpAdapter.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class EligibleGpuOfferingProblemHandler {

	@ExceptionHandler(GpuOfferingException.class)
	ResponseEntity<Problem> handle(GpuOfferingException failure, HttpServletRequest request) {
		HttpStatus status = failure instanceof GpuOfferingNotFoundException ? HttpStatus.NOT_FOUND
				: failure instanceof GpuOfferingConflictException ? HttpStatus.CONFLICT
						: HttpStatus.UNPROCESSABLE_ENTITY;
		String detail = failure.getMessage().substring(failure.getMessage().indexOf(": ") + 2);
		return response(status, detail, "SKYWRIGHT_" + failure.code(), request);
	}

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ResponseEntity<Problem> handleConcurrentUpdate(ObjectOptimisticLockingFailureException failure,
			HttpServletRequest request) {
		return response(HttpStatus.CONFLICT, "The Eligible GPU Offering changed concurrently; reload it and retry.",
				"SKYWRIGHT_GPU_OFFERING_REVISION_CONFLICT", request);
	}

	private static ResponseEntity<Problem> response(HttpStatus status, String detail, String code,
			HttpServletRequest request) {
		Problem problem = new Problem("about:blank", status.getReasonPhrase(), status.value(), detail,
				request.getRequestURI(), code, RequestCorrelationFilter.correlationIdFrom(request), List.of(), null,
				false);
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
	}

}
