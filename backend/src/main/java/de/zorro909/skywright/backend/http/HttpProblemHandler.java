package de.zorro909.skywright.backend.http;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public final class HttpProblemHandler extends ResponseEntityExceptionHandler {
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception exception,
      Object ignoredBody,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    return problemResponse(exception, headers, status, (ServletWebRequest) request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> handleUnexpectedException(Exception exception, ServletWebRequest request) {
    logger.error(
        "Unhandled HTTP failure with correlation ID "
            + RequestCorrelationFilter.correlationIdFrom(request.getRequest()),
        exception);
    return problemResponse(exception, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
  }

  private ResponseEntity<Object> problemResponse(
      Exception exception,
      HttpHeaders sourceHeaders,
      HttpStatusCode status,
      ServletWebRequest webRequest) {
    var request = webRequest.getRequest();
    var responseHeaders = new HttpHeaders();
    responseHeaders.putAll(sourceHeaders);
    responseHeaders.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    var problem = HttpProblemDetails.from(exception, status, request);
    return new ResponseEntity<>(problem, responseHeaders, status);
  }
}
