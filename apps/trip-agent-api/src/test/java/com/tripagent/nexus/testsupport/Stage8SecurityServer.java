package com.tripagent.nexus.testsupport;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.tripagent.nexus.TripAgentApiApplication;
import com.tripagent.nexus.application.service.DocumentApplicationService;
import com.tripagent.nexus.application.service.FeedbackApplicationService;
import com.tripagent.nexus.application.service.PlanApplicationService;
import com.tripagent.nexus.application.service.TripApplicationService;
import com.tripagent.nexus.controller.dto.ApiModels.CreateFeedbackRequest;
import com.tripagent.nexus.controller.dto.ApiModels.CreateTravelPlanRequest;
import com.tripagent.nexus.controller.dto.ApiModels.CreateTripRequest;
import com.tripagent.nexus.controller.dto.ApiModels.SaveTravelRequirementRequest;
import com.tripagent.nexus.domain.model.DomainEnums.BookingReviewStatus;
import com.tripagent.nexus.domain.model.DomainEnums.BookingType;
import com.tripagent.nexus.infrastructure.persistence.entity.ParsedBookingEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.ParsedBookingRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringApplication;

/** Local-only security target. No production credential or external provider is loaded. */
public final class Stage8SecurityServer {
  public static void main(String[] args) throws Exception {
    Path directory = Path.of(System.getProperty("stage8.runtime")).toAbsolutePath();
    Files.createDirectories(directory);
    KeyPair identityKey = KeyPairGenerator.getInstance("RSA").generateKeyPair();
    var jaeger = new WireMockServer(wireMockConfig().bindAddress("127.0.0.1").dynamicPort());
    jaeger.start();
    String issuer = jaeger.baseUrl() + "/stage8-issuer";
    jaeger.stubFor(
        get(urlPathEqualTo("/.well-known/jwks.json"))
            .willReturn(okJson(jwkSet((RSAPublicKey) identityKey.getPublic()))));
    jaeger.stubFor(
        get(urlPathMatching("/api/traces/[0-9a-f]{32}"))
            .willReturn(
                okJson(
                    """
            {"data":[{"spans":[{"spanID":"1111111111111111","operationName":"synthetic-stage8",
            "startTime":1790000000000000,"duration":1000,"references":[],
            "tags":[{"key":"user.name","value":"SYNTHETIC_PRIVATE_MARKER"},
            {"key":"prompt","value":"SYNTHETIC_PROMPT_MARKER"}]}]}]}
            """)));
    try (var postgres =
        EmbeddedPostgres.builder()
            .setPort(0)
            .setServerConfig("listen_addresses", "127.0.0.1")
            .start()) {
      var application = new SpringApplication(TripAgentApiApplication.class);
      try (var context =
          application.run(
              "--spring.profiles.active=test,mock-model",
              "--server.address=127.0.0.1",
              "--server.port=0",
              "--spring.datasource.url=" + postgres.getJdbcUrl("postgres", "postgres"),
              "--spring.datasource.username=postgres",
              "--spring.datasource.password=",
              "--app.upload.storage-root=" + directory.resolve("uploads"),
              "--app.security.enabled=true",
              "--app.security.issuer-uri=" + issuer,
              "--app.security.audience=tripagent-api",
              "--app.security.jwk-set-uri=" + jaeger.baseUrl() + "/.well-known/jwks.json",
              "--app.developer-tools.enabled=true",
              "--app.developer-tools.jaeger-base-url=" + jaeger.baseUrl(),
              "--app.maps.enabled=false")) {
        var mapper = context.getBean(ObjectMapper.class);
        var trips = context.getBean(TripApplicationService.class);
        UUID userA = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        var trip =
            trips.create(
                new CreateTripRequest(owner, "Synthetic private stage8 travel", "Asia/Shanghai"));
        var fixture = mapper.readTree(Path.of("qa/fixtures/trips/beijing-three-day.json").toFile());
        trips.saveRequirement(
            trip.tripId(),
            mapper.treeToValue(fixture.get("requirement"), SaveTravelRequirementRequest.class));
        var plan =
            context
                .getBean(PlanApplicationService.class)
                .create(new CreateTravelPlanRequest(trip.tripId(), "Synthetic private plan"));
        var feedback =
            context
                .getBean(FeedbackApplicationService.class)
                .create(
                    new CreateFeedbackRequest(
                        trip.tripId(),
                        plan.resourceId(),
                        "PLAN",
                        null,
                        "LIKE",
                        4,
                        "Synthetic private feedback",
                        List.of()));
        byte[] png =
            Base64.getDecoder()
                .decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");
        var document =
            context
                .getBean(DocumentApplicationService.class)
                .parse(trip.tripId(), "synthetic.png", "image/png", png, "stage8-seed-upload");
        UUID bookingId = UUID.randomUUID();
        context
            .getBean(ParsedBookingRepository.class)
            .saveAndFlush(
                new ParsedBookingEntity(
                    bookingId,
                    BookingType.HOTEL,
                    document.resourceId(),
                    "Synthetic provider",
                    null,
                    "Beijing",
                    "Synthetic hotel",
                    "Synthetic address",
                    null,
                    "SYNTHETIC-STAGE8-ONLY",
                    Instant.parse("2026-10-01T00:00:00Z"),
                    Instant.parse("2026-10-03T00:00:00Z"),
                    1,
                    new BigDecimal("100.0000"),
                    "CNY",
                    BigDecimal.ONE,
                    mapper.createArrayNode(),
                    BookingReviewStatus.NEEDS_REVIEW,
                    mapper.createArrayNode(),
                    mapper.createArrayNode(),
                    Instant.now()));
        Map<String, Object> seed =
            new LinkedHashMap<>(
                Map.of(
                    "baseUrl",
                    "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port"),
                    "userA",
                    userA.toString(),
                    "userB",
                    owner.toString(),
                    "tripB",
                    trip.tripId().toString(),
                    "documentB",
                    document.resourceId().toString(),
                    "taskB",
                    document.taskId().toString(),
                    "bookingB",
                    bookingId.toString(),
                    "traceId",
                    document.traceId()));
        seed.put("planB", plan.resourceId().toString());
        seed.put("feedbackB", feedback.feedbackId().toString());
        Instant tokenExpiry = Instant.now().plusSeconds(1800);
        String validA =
            token(
                mapper,
                identityKey.getPrivate(),
                issuer,
                "tripagent-api",
                userA,
                tokenExpiry,
                List.of("USER"));
        seed.put(
            "authTokens",
            Map.of(
                "validA",
                validA,
                "validB",
                token(
                    mapper,
                    identityKey.getPrivate(),
                    issuer,
                    "tripagent-api",
                    owner,
                    tokenExpiry,
                    List.of("USER")),
                "admin",
                token(
                    mapper,
                    identityKey.getPrivate(),
                    issuer,
                    "tripagent-api",
                    userA,
                    tokenExpiry,
                    List.of("ADMIN")),
                "expired",
                token(
                    mapper,
                    identityKey.getPrivate(),
                    issuer,
                    "tripagent-api",
                    userA,
                    Instant.now().minusSeconds(60),
                    List.of("USER")),
                "wrongIssuer",
                token(
                    mapper,
                    identityKey.getPrivate(),
                    "https://invalid.example.test",
                    "tripagent-api",
                    userA,
                    tokenExpiry,
                    List.of("USER")),
                "wrongAudience",
                token(
                    mapper,
                    identityKey.getPrivate(),
                    issuer,
                    "unrelated",
                    userA,
                    tokenExpiry,
                    List.of("USER")),
                "missingIdentity",
                token(
                    mapper,
                    identityKey.getPrivate(),
                    issuer,
                    "tripagent-api",
                    null,
                    tokenExpiry,
                    List.of("USER")),
                "badSignature",
                validA.substring(0, validA.length() - 1) + (validA.endsWith("A") ? "B" : "A")));
        mapper.writeValue(directory.resolve("server.json").toFile(), seed);
        new CountDownLatch(1)
            .await(
                Long.parseLong(System.getProperty("stage8.lifetimeSeconds", "1800")),
                TimeUnit.SECONDS);
      }
    } finally {
      jaeger.stop();
    }
  }

  private static String jwkSet(RSAPublicKey key) {
    return """
        {"keys":[{"kty":"RSA","kid":"stage8-key","use":"sig","alg":"RS256","n":"%s","e":"%s"}]}
        """
        .formatted(
            base64Url(unsigned(key.getModulus().toByteArray())),
            base64Url(unsigned(key.getPublicExponent().toByteArray())));
  }

  private static String token(
      ObjectMapper mapper,
      PrivateKey key,
      String issuer,
      String audience,
      UUID subject,
      Instant expiresAt,
      List<String> roles)
      throws Exception {
    String header =
        base64Url(
            mapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT", "kid", "stage8-key")));
    Map<String, Object> claims = new LinkedHashMap<>();
    claims.put("iss", issuer);
    claims.put("aud", List.of(audience));
    if (subject != null) {
      claims.put("sub", subject.toString());
    }
    claims.put("iat", Instant.now().getEpochSecond());
    claims.put("exp", expiresAt.getEpochSecond());
    claims.put("roles", roles);
    String payload = base64Url(mapper.writeValueAsBytes(claims));
    String signingInput = header + "." + payload;
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(key);
    signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
    return signingInput + "." + base64Url(signer.sign());
  }

  private static byte[] unsigned(byte[] value) {
    if (value.length > 1 && value[0] == 0) {
      return java.util.Arrays.copyOfRange(value, 1, value.length);
    }
    return value;
  }

  private static String base64Url(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }
}
