package com.tripagent.nexus.integration.messaging;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.tripagent.nexus.testsupport.ChaosEvidence.duration;
import static com.tripagent.nexus.testsupport.ChaosEvidence.value;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.tripagent.nexus.infrastructure.config.AsyncGovernanceProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.DocumentEntity;
import com.tripagent.nexus.integration.document.AiDocumentParserClient;
import com.tripagent.nexus.testsupport.ChaosEvidence;
import jakarta.validation.Validation;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class FastApiChaosTest {

  private static final String PATH = "/internal/v1/documents/parse";

  /**
   * Warm up JVM-wide cold-start costs once, before any timed assertion runs. On a fresh forked JVM
   * the first JDK {@link HttpClient} loopback call (selector thread / SSL context init), the first
   * multipart RestClient exchange, the first Jackson read and the first Hibernate Validator
   * validation together take a few seconds. Each parameter case creates its own HttpClient and
   * asserts the first call returns within the tight 5s external-call deadline, so without this
   * warm-up the very first case on a cold JVM can time out even though the production code is
   * correct. This exercises the exact real client path once with a generous warm-up deadline; it
   * does not change any production timeout or assertion.
   */
  @BeforeAll
  static void warmUpColdJvmNetworking() throws Exception {
    WireMockServer warmServer = new WireMockServer(0);
    warmServer.start();
    try (var validators = Validation.buildDefaultValidatorFactory()) {
      // Warm-up deadline is deliberately generous and never asserted: the only goal is to
      // pay the one-time JVM cold-start cost before the timed parameter cases run.
      var warmProperties = new AsyncGovernanceProperties();
      warmProperties.setExternalCallTimeout(Duration.ofSeconds(120));
      var warmExecutor = new ExternalCallExecutor(warmProperties);
      var mapper = new ObjectMapper().findAndRegisterModules();
      var requestFactory =
          new JdkClientHttpRequestFactory(
              HttpClient.newBuilder()
                  .version(HttpClient.Version.HTTP_1_1)
                  .proxy(java.net.ProxySelector.of(null))
                  .connectTimeout(Duration.ofSeconds(10))
                  .build());
      requestFactory.setReadTimeout(Duration.ofSeconds(120));
      var warmClient =
          new AiDocumentParserClient(
              RestClient.builder()
                  .baseUrl(warmServer.baseUrl())
                  .requestFactory(requestFactory)
                  .build(),
              mapper,
              validators.getValidator(),
              warmExecutor);
      warmServer.stubFor(
          post(PATH)
              .willReturn(
                  aResponse().withHeader("Content-Type", "application/json").withBody(VALID)));
      // Retry until the warm-up exchange succeeds. On the coldest forked JVM the first attempt
      // can itself time out while JDK HttpClient / Hibernate Validator / multipart / Jackson
      // classes are loaded; every attempt warms more of the JVM, so a subsequent attempt is
      // fast. This does not change any production timeout or the timed assertions below.
      RuntimeException warmError = null;
      for (int attempt = 1; attempt <= 5; attempt++) {
        try {
          warmClient.parse(
              mockDocument(),
              UUID.randomUUID(),
              new byte[] {1},
              "en",
              UUID.randomUUID().toString().replace("-", ""));
          warmError = null;
          break;
        } catch (RuntimeException warmAttempt) {
          warmError = warmAttempt;
          Thread.sleep(500L);
        }
      }
      if (warmError != null) {
        throw warmError;
      }
      warmExecutor.close();
    } finally {
      warmServer.stop();
    }
  }

  private static DocumentEntity mockDocument() {
    var document = mock(DocumentEntity.class);
    when(document.getDocumentId()).thenReturn(UUID.randomUUID());
    when(document.getMediaType()).thenReturn("image/png");
    when(document.getFileName()).thenReturn("synthetic.png");
    when(document.getSha256()).thenReturn("0".repeat(64));
    return document;
  }

  private static final String VALID =
      """
      {"bookings":[{"type":"HOTEL","confidence":"0.9","missingFields":[],
      "fieldEvidence":[]}],"warnings":[],"confidence":"0.9","parserMode":"MOCK",
      "parserVersion":"synthetic-1","ocrEngine":"synthetic"}
      """;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "refused",
        "http500",
        "http429",
        "timeout",
        "json",
        "semantic",
        "missingCollection",
        "nullResponse"
      })
  void parserFaultIsBoundedClassifiedAndRecoverable(String fault) throws Exception {
    ChaosEvidence.run(
        "fastapi." + fault,
        "LOCAL_HTTP_STUB",
        "Real Java multipart parser client -> loopback WireMock; no OCR or model invoked",
        record -> {
          WireMockServer server = new WireMockServer(0);
          server.start();
          var properties = new AsyncGovernanceProperties();
          properties.setExternalCallTimeout(duration("assertionTimeoutMs"));
          var executor = new ExternalCallExecutor(properties);
          try (var validators = Validation.buildDefaultValidatorFactory()) {
            var mapper = new ObjectMapper().findAndRegisterModules();
            var requestFactory =
                new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        // Force a direct connection for loopback WireMock calls: on a cold JVM on
                        // Windows the default ProxySelector can delay the first connection to a
                        // fresh
                        // loopback port past callTimeoutMs (200ms), misclassifying an invalid
                        // response as a
                        // retriable timeout. ProxySelector.of(null) means never proxy; production
                        // logic is unchanged.
                        .proxy(java.net.ProxySelector.of(null))
                        // TCP connect for loopback is not what the chaos 200ms deadline
                        // measures; the fault deadline is enforced by externalCallTimeout
                        // below. A generous connect budget avoids cold per-server Jetty
                        // first-connection jitter masquerading as a retriable timeout.
                        .connectTimeout(duration("assertionTimeoutMs"))
                        .build());
            requestFactory.setReadTimeout(duration("assertionTimeoutMs"));
            var client =
                new AiDocumentParserClient(
                    RestClient.builder()
                        .baseUrl(server.baseUrl())
                        .requestFactory(requestFactory)
                        .build(),
                    mapper,
                    validators.getValidator(),
                    executor);
            var document = mockDocument();
            UUID taskId = UUID.randomUUID();
            String traceId = UUID.randomUUID().toString().replace("-", "");
            record.trace(traceId, "traceparent propagated to loopback HTTP stub; no exported span");
            server.stubFor(
                post(PATH)
                    .willReturn(
                        aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody(VALID)));
            assertThat(client.parse(document, taskId, new byte[] {1}, "en", traceId).bookings())
                .hasSize(1);
            server.resetRequests();
            properties.setExternalCallTimeout(duration("callTimeoutMs"));
            var response =
                aResponse().withHeader("Content-Type", "application/json").withBody(VALID);
            switch (fault) {
              case "http500" -> response.withStatus(500);
              case "http429" -> response.withStatus(429).withHeader("Retry-After", "1");
              case "timeout" -> response.withFixedDelay(value("callTimeoutMs") * 3);
              case "json" -> response.withBody("{invalid");
              case "semantic" ->
                  response.withBody(
                      "{\"bookings\":[],\"warnings\":[],\"confidence\":\"0.9\","
                          + "\"parserMode\":\"MOCK\",\"parserVersion\":\"synthetic-1\",\"ocrEngine\":\"synthetic\"}");
              case "nullResponse" -> response.withBody("null");
              case "missingCollection" ->
                  response.withBody("{\"confidence\":\"0.9\",\"parserMode\":\"MOCK\"}");
              default -> {}
            }
            server.stubFor(post(PATH).willReturn(response));
            AiDocumentParserClient failingClient = client;
            if (fault.equals("refused")) {
              int unusedPort;
              try (var socket = new ServerSocket(0)) {
                unusedPort = socket.getLocalPort();
              }
              failingClient =
                  new AiDocumentParserClient(
                      RestClient.builder()
                          .baseUrl("http://127.0.0.1:" + unusedPort)
                          .requestFactory(requestFactory)
                          .build(),
                      mapper,
                      validators.getValidator(),
                      executor);
            }
            var injectedClient = failingClient;
            record.injected();
            long started = System.nanoTime();
            var failure =
                catchThrowableOfType(
                    () -> injectedClient.parse(document, taskId, new byte[] {1}, "en", traceId),
                    AsyncOperationException.class);
            record.detected();
            assertThat(failure).isNotNull();
            boolean invalid =
                java.util.Set.of("json", "semantic", "missingCollection", "nullResponse")
                    .contains(fault);
            assertThat(failure.retryable()).isEqualTo(!invalid);
            assertThat(failure.code())
                .isEqualTo(
                    invalid
                        ? "DOCUMENT_PARSER_RESPONSE_INVALID"
                        : fault.equals("timeout")
                            ? "EXTERNAL_CALL_TIMEOUT"
                            : "DOCUMENT_PARSER_UNAVAILABLE");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isLessThan(duration("assertionTimeoutMs"));
            record.measure("failureCode", failure.code());
            record.measure("retryable", failure.retryable());
            record.measure(
                "clientAttempts",
                fault.equals("refused")
                    ? 0
                    : server.findAll(postRequestedFor(urlEqualTo(PATH))).size());
            if (!fault.equals("refused")) {
              server.verify(1, postRequestedFor(urlEqualTo(PATH)));
              assertThat(
                      server.getAllServeEvents().getFirst().getRequest().getHeader("traceparent"))
                  .contains(traceId);
            }
            record.degraded(failure.userMessage());
            server.stubFor(
                post(PATH)
                    .willReturn(
                        aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody(VALID)));
            record.cleared();
            properties.setExternalCallTimeout(duration("assertionTimeoutMs"));
            assertThat(client.parse(document, taskId, new byte[] {1}, "en", traceId).bookings())
                .hasSize(1);
            record.recovered();
          } finally {
            executor.close();
            server.stop();
          }
        });
  }
}
