package de.zorro909.skywright.backend.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

final class RequestCorrelationFilterTest {
  @Test
  void validIncomingIdentifierBecomesRequestAndResponseContext() throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader(RequestCorrelationFilter.HEADER_NAME, "request.92:unit");
    var response = new MockHttpServletResponse();

    new RequestCorrelationFilter().doFilter(request, response, new MockFilterChain());

    assertThat(RequestCorrelationFilter.correlationIdFrom(request)).isEqualTo("request.92:unit");
    assertThat(response.getHeader(RequestCorrelationFilter.HEADER_NAME))
        .isEqualTo("request.92:unit");
    assertThat(MDC.get("correlationId")).isNull();
  }

  @Test
  void invalidIncomingIdentifierIsReplacedByUuid() throws Exception {
    var request = new MockHttpServletRequest();
    request.addHeader(RequestCorrelationFilter.HEADER_NAME, "not/valid");
    var response = new MockHttpServletResponse();

    new RequestCorrelationFilter().doFilter(request, response, new MockFilterChain());

    var correlationId = RequestCorrelationFilter.correlationIdFrom(request);
    assertThat(UUID.fromString(correlationId).toString()).isEqualTo(correlationId);
  }
}
