package com.tripagent.nexus.infrastructure.config;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
public class ApiSecurityConfiguration {

  @Bean
  SecurityFilterChain apiSecurityFilterChain(
      HttpSecurity http,
      SecurityProperties properties,
      ApiSecurityErrorHandler securityErrorHandler)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(Customizer.withDefaults())
        .headers(
            headers ->
                headers
                    .contentSecurityPolicy(
                        policy ->
                            policy.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                    .referrerPolicy(
                        policy ->
                            policy.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable());

    if (!properties.isEnabled()) {
      http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
      return http.build();
    }

    http.authorizeHttpRequests(
            requests ->
                requests
                    .requestMatchers(
                        "/api/v1/health",
                        "/api/v1/system/status",
                        "/api/v1/maps/tiles/**",
                        "/api/v1/operations/**",
                        "/api/v1/evaluations/**",
                        "/actuator/health/**",
                        "/error",
                        "/mcp",
                        "/mcp/**",
                        "/v3/api-docs",
                        "/v3/api-docs/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            exceptions ->
                exceptions
                    .authenticationEntryPoint(securityErrorHandler)
                    .accessDeniedHandler(securityErrorHandler))
        .oauth2ResourceServer(
            resourceServer ->
                resourceServer
                    .authenticationEntryPoint(securityErrorHandler)
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));
    return http.build();
  }

  @Bean
  JwtDecoder jwtDecoder(SecurityProperties properties) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.getJwkSetUri()).build();
    OAuth2TokenValidator<Jwt> issuer =
        JwtValidators.createDefaultWithIssuer(properties.getIssuerUri());
    OAuth2TokenValidator<Jwt> audience =
        new JwtClaimValidator<List<String>>(
            "aud", values -> values != null && values.contains(properties.getAudience()));
    OAuth2TokenValidator<Jwt> subject =
        new JwtClaimValidator<String>("sub", ApiSecurityConfiguration::isUuid);
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience, subject));
    return decoder;
  }

  private static JwtAuthenticationConverter jwtAuthenticationConverter() {
    JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
    roles.setAuthoritiesClaimName("roles");
    roles.setAuthorityPrefix("ROLE_");
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(roles);
    return converter;
  }

  private static boolean isUuid(String value) {
    try {
      UUID.fromString(value);
      return true;
    } catch (IllegalArgumentException | NullPointerException exception) {
      return false;
    }
  }
}
