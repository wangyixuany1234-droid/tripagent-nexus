package com.tripagent.nexus.integration.ai;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.tripagent.nexus.infrastructure.config.AiServiceProperties;
import com.tripagent.nexus.infrastructure.config.JacksonConfiguration;
import com.tripagent.nexus.infrastructure.persistence.entity.DocumentEntity;
import com.tripagent.nexus.integration.document.AiDocumentParserClient;
import com.tripagent.nexus.integration.messaging.ExternalCallExecutor;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class AiRetrievalClientConfigurationTest {

  private WireMockServer server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  void documentClientUsesHttp11AndWritesMultipartFields() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    server.stubFor(
        post(urlEqualTo("/internal/v1/documents/parse"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .withBody(
                        """
                        {
                          "bookings": [{
                            "type": "HOTEL",
                            "provider": "Example Hotel",
                            "origin": null,
                            "destination": null,
                            "hotelName": "Shinjuku Central Hotel",
                            "hotelAddress": "1-2-3 Shinjuku, Tokyo",
                            "transportNumber": null,
                            "confirmationNumber": "HOTEL-NO-DATE",
                            "startTime": null,
                            "endTime": null,
                            "travelerCount": 2,
                            "amount": "4500.0000",
                            "currency": "CNY",
                            "confidence": "0.6200",
                            "missingFields": ["startTime", "endTime"],
                            "fieldEvidence": [{
                              "field": "hotelName",
                              "confidence": "0.9600",
                              "textEvidence": "Shinjuku Central Hotel",
                              "region": {
                                "page": 1,
                                "x": "0.1000",
                                "y": "0.1000",
                                "width": "0.5000",
                                "height": "0.0500"
                              }
                            }]
                          }],
                          "warnings": ["MOCK parser mode: deterministic fixture output"],
                          "confidence": "0.9000",
                          "parserMode": "MOCK",
                          "parserVersion": "document-parser-1.0.0",
                          "ocrEngine": "mock-ocr"
                        }
                        """)));

    AiServiceProperties properties = new AiServiceProperties();
    properties.setBaseUrl(URI.create(server.baseUrl()));
    properties.setServiceToken("synthetic-test-token");
    properties.setDocumentTimeout(Duration.ofSeconds(5));
    byte[] content = "synthetic-document".getBytes(StandardCharsets.UTF_8);

    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                JacksonAutoConfiguration.class,
                HttpMessageConvertersAutoConfiguration.class,
                RestClientAutoConfiguration.class))
        .withUserConfiguration(JacksonConfiguration.class, AiRetrievalClientConfiguration.class)
        .withPropertyValues("spring.jackson.mapper.allow-coercion-of-scalars=false")
        .withBean(AiServiceProperties.class, () -> properties)
        .run(
            context -> {
              var client =
                  context.getBean(
                      "aiDocumentRestClient", org.springframework.web.client.RestClient.class);
              ExternalCallExecutor callExecutor = mock(ExternalCallExecutor.class);
              doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(2)).get())
                  .when(callExecutor)
                  .execute(eq("document-parser"), eq(1), any());
              DocumentEntity document =
                  new DocumentEntity(
                      UUID.fromString("11111111-1111-4111-8111-111111111111"),
                      UUID.fromString("33333333-3333-4333-8333-333333333333"),
                      "synthetic.png",
                      MediaType.IMAGE_PNG_VALUE,
                      content.length,
                      "0".repeat(64),
                      "synthetic/storage/key",
                      1,
                      Instant.parse("2026-09-10T00:00:00Z"));

              try (var validatorFactory = Validation.buildDefaultValidatorFactory()) {
                var parserClient =
                    new AiDocumentParserClient(
                        client,
                        context.getBean(ObjectMapper.class),
                        validatorFactory.getValidator(),
                        callExecutor);
                BookingExtractionResult result =
                    parserClient.parse(
                        document,
                        UUID.fromString("22222222-2222-4222-8222-222222222222"),
                        content,
                        "zh-CN",
                        "0".repeat(32));

                var request = server.getAllServeEvents().getFirst().getRequest();
                assertThat(result).isNotNull();
                assertThat(result.bookings()).hasSize(1);
                assertThat(result.bookings().getFirst().amount())
                    .isEqualByComparingTo(new BigDecimal("4500.0000"));
                assertThat(result.parserMode()).isEqualTo("MOCK");
                assertThat(request.getHeader("Upgrade")).isNull();
                assertThat(request.getBodyAsString())
                    .contains(
                        "name=\"documentId\"",
                        "name=\"taskId\"",
                        "name=\"mediaType\"",
                        "name=\"sha256\"",
                        "name=\"file\"");
              }
            });
  }
}
