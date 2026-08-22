package de.zorro909.skywright.backend.datasetcatalog;

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

@RestControllerAdvice(assignableTypes = DatasetCatalogHttpAdapter.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
final class DatasetCatalogProblemHandler {

	@ExceptionHandler(DatasetCatalogException.class)
	ResponseEntity<Problem> handle(DatasetCatalogException failure, HttpServletRequest request) {
		HttpStatus status = failure instanceof DatasetStorageUnavailableException ? HttpStatus.SERVICE_UNAVAILABLE
				: failure instanceof DatasetCatalogNotFoundException ? HttpStatus.NOT_FOUND
						: failure instanceof DatasetCatalogConflictException ? HttpStatus.CONFLICT
								: HttpStatus.UNPROCESSABLE_ENTITY;
		String source = failure instanceof DatasetStorageUnavailableException unavailable ? unavailable.source() : null;
		return problem(status, publicDetail(failure), failure.errorCode(), source,
				failure instanceof DatasetStorageUnavailableException, request);
	}

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ResponseEntity<Problem> handleConcurrentUpdate(ObjectOptimisticLockingFailureException failure,
			HttpServletRequest request) {
		return problem(HttpStatus.CONFLICT, "The Dataset Catalog changed; reload it and retry.",
				"DATASET_CATALOG_REVISION_CONFLICT", null, false, request);
	}

	private static ResponseEntity<Problem> problem(HttpStatus status, String detail, String code, String source,
			boolean retryable, HttpServletRequest request) {
		Problem body = new Problem("about:blank", status.getReasonPhrase(), status.value(), detail,
				request.getRequestURI(), "SKYWRIGHT_" + code, RequestCorrelationFilter.correlationIdFrom(request),
				List.of(), source, retryable);
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
	}

	private static String publicDetail(DatasetCatalogException failure) {
		if (failure instanceof DatasetStorageUnavailableException) {
			return "Dataset storage is temporarily unavailable; retry the request.";
		}
		if (failure instanceof DatasetCatalogNotFoundException) {
			return "The requested Dataset Catalog resource does not exist.";
		}
		if (failure instanceof DatasetCatalogConflictException) {
			return "The Dataset Catalog request conflicts with its current state.";
		}
		return "The Dataset Catalog request could not be completed.";
	}

}
