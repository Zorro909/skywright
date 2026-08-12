package de.zorro909.skywright.backend.http;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

@Configuration(proxyBeanMethods = false)
public class WebApplicationResources implements WebMvcConfigurer {

	private static final String[] STATIC_LOCATIONS = { "classpath:/META-INF/resources/", "classpath:/resources/",
			"classpath:/static/", "classpath:/public/" };

	private static final Set<String> RESERVED_NAMESPACES = Set.of("api", "openapi", "livez", "readyz", "actuator",
			"assets", "proxy");

	private static final Pattern FILE_NAME = Pattern.compile("(?:^|/)[^/]+\\.[^/]+$");

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/*.css", "/*.js")
			.addResourceLocations(STATIC_LOCATIONS)
			.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
			.resourceChain(true)
			.addResolver(new PathResourceResolver());
		registry.addResourceHandler("/**")
			.addResourceLocations(STATIC_LOCATIONS)
			.setCacheControl(CacheControl.maxAge(Duration.ZERO).mustRevalidate())
			.resourceChain(true)
			.addResolver(new ApplicationRouteResolver());
	}

	static boolean isApplicationRoute(String path) {
		var normalized = path.startsWith("/") ? path.substring(1) : path;
		if (normalized.isEmpty()) {
			return true;
		}
		var firstSegment = normalized.split("/", 2)[0];
		return !RESERVED_NAMESPACES.contains(firstSegment) && !FILE_NAME.matcher(normalized).find();
	}

	private static final class ApplicationRouteResolver extends PathResourceResolver {

		@Override
		protected Resource getResource(String resourcePath, Resource location) throws IOException {
			var resource = super.getResource(resourcePath, location);
			if (resource != null && resource.isReadable() && !resourcePath.isEmpty()) {
				return resource;
			}
			return isApplicationRoute(resourcePath) ? location.createRelative("index.html") : null;
		}

	}

}
