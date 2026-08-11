package de.zorro909.skywright.backend.http;

import de.zorro909.skywright.backend.boundary.generated.model.FieldViolation;
import de.zorro909.skywright.backend.boundary.generated.model.Problem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.MethodArgumentNotValidException;

final class HttpProblemDetails {
  private HttpProblemDetails() {}

  static Problem from(HttpStatusCode status, HttpServletRequest request) {
    return from(null, status, request);
  }

  static Problem from(Exception exception, HttpStatusCode status, HttpServletRequest request) {
    var metadata = metadata(status);
    return new Problem(
        "about:blank",
        title(status),
        status.value(),
        metadata.detail(),
        request.getRequestURI(),
        metadata.errorCode(),
        RequestCorrelationFilter.correlationIdFrom(request),
        fieldViolations(exception));
  }

  private static String title(HttpStatusCode status) {
    var knownStatus = HttpStatus.resolve(status.value());
    return knownStatus == null ? "HTTP Error" : knownStatus.getReasonPhrase();
  }

  private static Metadata metadata(HttpStatusCode status) {
    return switch (status.value()) {
      case 400 -> new Metadata("The request is invalid.", "SKYWRIGHT_HTTP_BAD_REQUEST");
      case 404 -> new Metadata("No HTTP resource exists at this path.", "SKYWRIGHT_HTTP_NOT_FOUND");
      case 405 ->
          new Metadata(
              "The HTTP method is not supported for this path.",
              "SKYWRIGHT_HTTP_METHOD_NOT_ALLOWED");
      case 406 ->
          new Metadata(
              "The requested response representation is not available.",
              "SKYWRIGHT_HTTP_NOT_ACCEPTABLE");
      case 415 ->
          new Metadata(
              "The request media type is not supported.", "SKYWRIGHT_HTTP_UNSUPPORTED_MEDIA_TYPE");
      default ->
          status.is5xxServerError()
              ? new Metadata(
                  "The server could not complete the request.", "SKYWRIGHT_HTTP_INTERNAL_ERROR")
              : new Metadata(
                  "The HTTP request could not be completed.", "SKYWRIGHT_HTTP_REQUEST_FAILED");
    };
  }

  private static List<FieldViolation> fieldViolations(Exception exception) {
    if (!(exception instanceof MethodArgumentNotValidException validationFailure)) {
      return List.of();
    }
    return validationFailure.getBindingResult().getFieldErrors().stream()
        .map(
            fieldError ->
                new FieldViolation(
                    fieldError.getField(), "INVALID_VALUE", "The field value is invalid."))
        .toList();
  }

  private record Metadata(String detail, String errorCode) {}
}
