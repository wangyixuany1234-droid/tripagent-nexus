package com.tripagent.nexus.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Local-only JWT support for {@code @SpringBootTest} HTTP tests that exercise the real OAuth2
 * resource-server security chain. Tokens are signed with an in-memory RSA key and validated by a
 * matching {@link JwtDecoder}; no external identity provider is contacted.
 */
public final class TestJwts {

  public static final String ISSUER = "http://127.0.0.1:65535/test-issuer";
  public static final String AUDIENCE = "tripagent-api";
  public static final String KEY_ID = "test-key";

  private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
  private static final KeyPair KEY_PAIR = generateKeyPair();

  private TestJwts() {}

  /** A normal end-user token for the given subject. */
  public static String userToken(UUID subject) {
    return token(subject, List.of("USER"), Instant.now().plusSeconds(3600));
  }

  /** An administrator token for the given subject (also carries the USER role). */
  public static String adminToken(UUID subject) {
    return token(subject, List.of("ADMIN", "USER"), Instant.now().plusSeconds(3600));
  }

  public static String token(UUID subject, List<String> roles, Instant expiresAt) {
    try {
      String header =
          base64Url(MAPPER.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT", "kid", KEY_ID)));
      Map<String, Object> claims = new LinkedHashMap<>();
      claims.put("iss", ISSUER);
      claims.put("aud", List.of(AUDIENCE));
      if (subject != null) {
        claims.put("sub", subject.toString());
      }
      claims.put("iat", Instant.now().getEpochSecond());
      claims.put("exp", expiresAt.getEpochSecond());
      claims.put("roles", roles);
      String payload = base64Url(MAPPER.writeValueAsBytes(claims));
      String signingInput = header + "." + payload;
      Signature signer = Signature.getInstance("SHA256withRSA");
      signer.initSign((RSAPrivateKey) KEY_PAIR.getPrivate());
      signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
      return signingInput + "." + base64Url(signer.sign());
    } catch (Exception exception) {
      throw new IllegalStateException("Failed to sign test JWT", exception);
    }
  }

  /** Builds a decoder that validates tokens produced by this helper. */
  public static JwtDecoder decoder() {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEY_PAIR.getPublic()).build();
    OAuth2TokenValidator<Jwt> issuer = JwtValidators.createDefaultWithIssuer(ISSUER);
    OAuth2TokenValidator<Jwt> audience =
        new JwtClaimValidator<List<String>>(
            "aud", values -> values != null && values.contains(AUDIENCE));
    OAuth2TokenValidator<Jwt> subject = new JwtClaimValidator<String>("sub", TestJwts::isUuid);
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuer, audience, subject));
    return decoder;
  }

  private static boolean isUuid(String value) {
    try {
      UUID.fromString(value);
      return true;
    } catch (IllegalArgumentException | NullPointerException exception) {
      return false;
    }
  }

  private static KeyPair generateKeyPair() {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(2048);
      return generator.generateKeyPair();
    } catch (Exception exception) {
      throw new IllegalStateException("Failed to generate test RSA key pair", exception);
    }
  }

  private static String base64Url(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  /** Test configuration that replaces the JWKS-based decoder with the in-memory decoder. */
  @Configuration
  public static class TestJwtSecurityConfig {
    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
      return TestJwts.decoder();
    }
  }
}
