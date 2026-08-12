package de.zorro909.skywright.backend.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestCorrelationFilter extends OncePerRequestFilter {

	public static final String HEADER_NAME = "X-Correlation-ID";

	public static final String REQUEST_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".id";

	private static final String LOG_CONTEXT_KEY = "correlationId";

	private static final Pattern VALID_IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		var correlationId = effectiveCorrelationId(request.getHeader(HEADER_NAME));
		request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
		response.setHeader(HEADER_NAME, correlationId);
		MDC.put(LOG_CONTEXT_KEY, correlationId);
		try {
			filterChain.doFilter(request, response);
		}
		finally {
			MDC.remove(LOG_CONTEXT_KEY);
		}
	}

	public static String correlationIdFrom(HttpServletRequest request) {
		return (String) request.getAttribute(REQUEST_ATTRIBUTE);
	}

	private static String effectiveCorrelationId(String candidate) {
		if (candidate != null && VALID_IDENTIFIER.matcher(candidate).matches()) {
			return candidate;
		}
		return UUID.randomUUID().toString();
	}

}
