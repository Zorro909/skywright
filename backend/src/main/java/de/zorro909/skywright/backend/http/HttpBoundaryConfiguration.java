package de.zorro909.skywright.backend.http;

import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class HttpBoundaryConfiguration {
  @Bean
  WebServerFactoryCustomizer<TomcatServletWebServerFactory> routeTraceThroughHttpBoundary() {
    // Let the boundary filter reject TRACE instead of Tomcat reflecting request data.
    return factory -> factory.addConnectorCustomizers(connector -> connector.setAllowTrace(true));
  }
}
