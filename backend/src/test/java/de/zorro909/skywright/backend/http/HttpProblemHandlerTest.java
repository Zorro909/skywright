package de.zorro909.skywright.backend.http;

import static org.assertj.core.api.Assertions.assertThat;

import de.zorro909.skywright.backend.boundary.generated.model.Problem;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.ServletWebRequest;

final class HttpProblemHandlerTest {
  @Test
  void handledFailureUsesSafeCorrelatedProblem() {
    var request = correlatedRequest("/api/v1/missing", "request-92");

    var response =
        new HttpProblemHandler()
            .handleExceptionInternal(
                new IllegalArgumentException("secret"),
                null,
                new HttpHeaders(),
                HttpStatus.NOT_FOUND,
                new ServletWebRequest(request));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getHeaders().getContentType().toString())
        .isEqualTo("application/problem+json");
    assertThat(response.getBody())
        .isEqualTo(
            new Problem(
                "about:blank",
                "Not Found",
                404,
                "No HTTP resource exists at this path.",
                "/api/v1/missing",
                "SKYWRIGHT_HTTP_NOT_FOUND",
                "request-92",
                java.util.List.of()));
  }

  @Test
  void validationFailureContainsOnlySanitizedFieldViolation() throws Exception {
    var bindingResult = new BeanPropertyBindingResult(new Object(), "request");
    bindingResult.addError(new FieldError("request", "name", "secret rejection"));
    var method = HttpProblemHandlerTest.class.getDeclaredMethod("validatedParameter", String.class);
    var exception =
        new MethodArgumentNotValidException(
            new org.springframework.core.MethodParameter(method, 0), bindingResult);

    var response =
        new HttpProblemHandler()
            .handleExceptionInternal(
                exception,
                null,
                new HttpHeaders(),
                HttpStatus.BAD_REQUEST,
                new ServletWebRequest(correlatedRequest("/api/v1/jobs", "request-validation")));
    var problem = (Problem) response.getBody();

    assertThat(problem.getFieldViolations())
        .singleElement()
        .satisfies(
            violation -> {
              assertThat(violation.getField()).isEqualTo("name");
              assertThat(violation.getCode()).isEqualTo("INVALID_VALUE");
              assertThat(violation.getMessage()).isEqualTo("The field value is invalid.");
            });
  }

  private static MockHttpServletRequest correlatedRequest(String path, String correlationId) {
    var request = new MockHttpServletRequest();
    request.setRequestURI(path);
    request.setAttribute(RequestCorrelationFilter.REQUEST_ATTRIBUTE, correlationId);
    return request;
  }

  @SuppressWarnings("unused")
  private static void validatedParameter(String value) {}
}
