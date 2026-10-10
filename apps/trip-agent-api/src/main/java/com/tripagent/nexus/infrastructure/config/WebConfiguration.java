package com.tripagent.nexus.infrastructure.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {

  private final SecurityProperties securityProperties;

  public WebConfiguration(SecurityProperties securityProperties) {
    this.securityProperties = securityProperties;
  }

  @Bean
  Clock systemClock() {
    return Clock.systemUTC();
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    registry
        .addMapping("/api/**")
        .allowedOrigins(securityProperties.getAllowedOrigins().toArray(String[]::new))
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        .allowedHeaders(
            "Authorization",
            "Content-Type",
            "Idempotency-Key",
            "Last-Event-ID",
            "X-Operations-Token",
            "X-Request-Id")
        .exposedHeaders("Location", "X-Request-Id", "X-Trace-Id")
        .allowCredentials(true)
        .maxAge(3600);
  }
}
