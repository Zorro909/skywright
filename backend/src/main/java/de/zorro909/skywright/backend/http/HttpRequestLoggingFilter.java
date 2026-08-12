package de.zorro909.skywright.backend.http;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Emits one bounded, value-safe event after each HTTP request. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public final class HttpRequestLoggingFilter extends OncePerRequestFilter {

	private static final Logger logger = LoggerFactory.getLogger(HttpRequestLoggingFilter.class);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		var startedAt = System.nanoTime();
		Throwable failure = null;
		try {
			filterChain.doFilter(request, response);
		}
		catch (ServletException | IOException | RuntimeException | Error exception) {
			failure = exception;
			throw exception;
		}
		finally {
			logRequest(request, response, startedAt, failure);
		}
	}

	private static void logRequest(HttpServletRequest request, HttpServletResponse response, long startedAt,
			Throwable failure) {
		var event = failure == null ? logger.atInfo() : logger.atError();
		event.addKeyValue("event.duration", Math.max(0, System.nanoTime() - startedAt))
			.addKeyValue("http.request.method", request.getMethod())
			.addKeyValue("http.response.status_code", failure == null ? response.getStatus() : 500);
		var route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
		if (route != null) {
			event.addKeyValue("http.route", route.toString());
		}
		if (failure != null) {
			event.addKeyValue("error.type", failure.getClass().getName())
				.addKeyValue("error.stack_trace", safeStackTrace(failure));
		}
		event.log("HTTP request completed");
	}

	private static String safeStackTrace(Throwable failure) {
		return Arrays.stream(failure.getStackTrace())
			.limit(64)
			.map(StackTraceElement::toString)
			.collect(Collectors.joining("\n"));
	}

}
