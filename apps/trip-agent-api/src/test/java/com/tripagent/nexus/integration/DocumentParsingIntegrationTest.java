package com.tripagent.nexus.integration;

import static com.tripagent.nexus.testsupport.OpenApiSchemaAssertions.assertResponse;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.RefreshMode.BEFORE_EACH_TEST_METHOD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.application.task.TaskEventStream;
import com.tripagent.nexus.domain.model.DomainEnums.BookingType;
import com.tripagent.nexus.domain.model.DomainEnums.DocumentStatus;
import com.tripagent.nexus.domain.model.TaskEvent;
import com.tripagent.nexus.infrastructure.persistence.entity.DocumentEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.DeadLetterEventRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.DocumentRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.OutboxEventRepository;
import com.tripagent.nexus.integration.document.AiDocumentParserClient;
import com.tripagent.nexus.integration.messaging.AsyncOperationException;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult.EvidenceRegion;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult.ExtractedBooking;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult.FieldEvidence;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(
    properties = {
      "app.document-processing.messaging-enabled=true",
      "app.async-governance.enabled=true",
      "app.async-governance.redis-enabled=false",
      "app.async-governance.max-attempts=3",
      "app.async-governance.initial-backoff=10ms",
      "app.async-governance.maximum-backoff=20ms",
      "app.async-governance.outbox-poll-interval=50ms",
      "app.async-governance.stale-task-threshold=1h",
      "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EmbeddedKafka(
    partitions = 1,
    topics = {"document.parse.requested", "document.parse.completed", "task.failed", "dead-letter"})
@AutoConfigureEmbeddedDatabase(
    provider = EMBEDDED,
    type = POSTGRES,
    refresh = BEFORE_EACH_TEST_METHOD)
class DocumentParsingIntegrationTest {

  private static final byte[] PNG =
      Base64.getDecoder()
          .decode(
              "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk"
                  + "+A8AAQUBAScY42YAAAAASUVORK5CYII=");

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private DocumentRepository documentRepository;
  @Autowired private DeadLetterEventRepository deadLetterRepository;
  @Autowired private OutboxEventRepository outboxRepository;
  @Autowired private TaskEventStream eventStream;

  @MockitoBean private AiDocumentParserClient parserClient;

  @Test
  void uploadParseReviewCorrectionAndDuplicateSuppressionWorkEndToEnd() throws Exception {
    when(parserClient.parse(
            any(DocumentEntity.class),
            any(UUID.class),
            any(byte[].class),
            anyString(),
            anyString()))
        .thenReturn(missingDateResult());
    UUID tripId = createTripWithRequirement();
    JsonNode accepted = upload(tripId, "missing-date.png", "document-upload-key-0001");
    JsonNode duplicate = upload(tripId, "missing-date-copy.png", "document-upload-key-0002");
    UUID documentId = UUID.fromString(accepted.get("resourceId").asText());
    UUID taskId = UUID.fromString(accepted.get("taskId").asText());

    assertThat(duplicate.get("resourceId").asText()).isEqualTo(documentId.toString());
    assertThat(duplicate.get("taskId").asText()).isEqualTo(taskId.toString());
    assertThat(documentRepository.count()).isEqualTo(1);
    assertThat(outboxRepository.count()).isGreaterThanOrEqualTo(1);

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
                    .isEqualTo(DocumentStatus.NEEDS_REVIEW));
    verify(parserClient, times(1))
        .parse(
            any(DocumentEntity.class),
            any(UUID.class),
            any(byte[].class),
            anyString(),
            anyString());

    MvcResult documentResult =
        mockMvc
            .perform(get("/api/v1/documents/{documentId}", documentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("NEEDS_REVIEW"))
            .andExpect(jsonPath("$.bookings[0].reviewStatus").value("NEEDS_REVIEW"))
            .andExpect(
                jsonPath("$.bookings[0].validationIssues[0].code")
                    .value("LOW_CONFIDENCE_CRITICAL_FIELD"))
            .andReturn();
    JsonNode document = read(documentResult);
    assertResponse("ParsedDocument", document);
    assertResponse("ParsedBooking", document.at("/bookings/0"));
    UUID bookingId = UUID.fromString(document.at("/bookings/0/bookingId").asText());
    awaitTaskStatus(taskId, "WAITING_CONFIRMATION");

    JsonNode corrected =
        read(
            mockMvc
                .perform(
                    patch(
                            "/api/v1/documents/{documentId}/bookings/{bookingId}",
                            documentId,
                            bookingId)
                        .header("Idempotency-Key", "booking-correction-key-0001")
                        .header("X-Actor-Id", "user-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "corrections": [
                                {"field": "startTime", "value": "2026-10-01T07:00:00Z"},
                                {"field": "endTime", "value": "2026-10-05T02:00:00Z"}
                              ],
                              "reason": "Verified against the confirmation email"
                            }
                            """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("CORRECTED"))
                .andExpect(jsonPath("$.corrections.length()").value(2))
                .andReturn());
    assertResponse("ParsedBooking", corrected);
    assertThat(corrected.at("/corrections/0/originalValue").asText()).isEmpty();
    assertThat(corrected.at("/corrections/0/correctedValue").asText())
        .isEqualTo("2026-10-01T07:00:00Z");
    assertThat(corrected.at("/corrections/0/actorId").asText()).isEqualTo("user-123");
    assertThat(corrected.at("/corrections/0/reason").asText())
        .isEqualTo("Verified against the confirmation email");
    assertThat(Instant.parse(corrected.at("/corrections/0/correctedAt").asText()).toString())
        .endsWith("Z");

    mockMvc
        .perform(get("/api/v1/tasks/{taskId}", taskId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    assertThat(eventStream.replay(taskId, null))
        .extracting(TaskEvent::stage)
        .contains("OCR_STARTED", "RULE_VALIDATION", "NEEDS_REVIEW", "COMPLETED");
  }

  @Test
  void temporaryParserFailureIsRetriedWithoutDuplicatingTheDocument() throws Exception {
    when(parserClient.parse(
            any(DocumentEntity.class),
            any(UUID.class),
            any(byte[].class),
            anyString(),
            anyString()))
        .thenThrow(
            new AsyncOperationException(
                "DOCUMENT_PARSER_UNAVAILABLE",
                true,
                "The document parser is temporarily unavailable."))
        .thenReturn(missingDateResult());

    UUID tripId = createTripWithRequirement();
    JsonNode accepted = upload(tripId, "retry.png", "document-retry-key-0001");
    UUID documentId = UUID.fromString(accepted.get("resourceId").asText());
    UUID taskId = UUID.fromString(accepted.get("taskId").asText());

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
                    .isEqualTo(DocumentStatus.NEEDS_REVIEW));

    verify(parserClient, times(2))
        .parse(
            any(DocumentEntity.class),
            any(UUID.class),
            any(byte[].class),
            anyString(),
            anyString());
    assertThat(documentRepository.count()).isEqualTo(1);
    assertThat(eventStream.replay(taskId, null))
        .extracting(TaskEvent::stage)
        .contains("RETRY_SCHEDULED", "NEEDS_REVIEW");
  }

  @Test
  void exhaustedParserRetriesEnterDeadLetterWithAReadableTaskFailure() throws Exception {
    when(parserClient.parse(
            any(DocumentEntity.class),
            any(UUID.class),
            any(byte[].class),
            anyString(),
            anyString()))
        .thenThrow(
            new AsyncOperationException(
                "DOCUMENT_PARSER_UNAVAILABLE",
                true,
                "The document parser remained unavailable after retries."));

    UUID tripId = createTripWithRequirement();
    JsonNode accepted = upload(tripId, "dead-letter.png", "document-dead-letter-key-0001");
    UUID documentId = UUID.fromString(accepted.get("resourceId").asText());
    UUID taskId = UUID.fromString(accepted.get("taskId").asText());

    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> {
              assertThat(deadLetterRepository.count()).isEqualTo(1);
              assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
                  .isEqualTo(DocumentStatus.FAILED);
            });
    verify(parserClient, times(3))
        .parse(
            any(DocumentEntity.class),
            any(UUID.class),
            any(byte[].class),
            anyString(),
            anyString());
    awaitTaskStatus(taskId, "FAILED");
    mockMvc
        .perform(get("/api/v1/tasks/{taskId}", taskId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.failureCode").value("DOCUMENT_PARSER_UNAVAILABLE"))
        .andExpect(
            jsonPath("$.failureReason")
                .value("The document parser remained unavailable after retries."));

    reset(parserClient);
    when(parserClient.parse(
            any(DocumentEntity.class),
            any(UUID.class),
            any(byte[].class),
            anyString(),
            anyString()))
        .thenReturn(missingDateResult());
    JsonNode retry =
        read(
            mockMvc
                .perform(
                    post("/api/v1/documents/{documentId}/retry", documentId)
                        .header("Idempotency-Key", "document-retry-key-0001"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.resourceId").value(documentId.toString()))
                .andReturn());
    UUID retryTaskId = UUID.fromString(retry.path("taskId").asText());
    assertThat(retryTaskId).isNotEqualTo(taskId);
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () -> {
              assertThat(documentRepository.findById(documentId).orElseThrow().getStatus())
                  .isEqualTo(DocumentStatus.NEEDS_REVIEW);
              mockMvc
                  .perform(get("/api/v1/tasks/{taskId}", retryTaskId))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.status").value("WAITING_CONFIRMATION"));
            });
    mockMvc
        .perform(get("/api/v1/tasks/{taskId}", retryTaskId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("WAITING_CONFIRMATION"))
        .andExpect(jsonPath("$.retryOfTaskId").value(taskId.toString()));
  }

  private void awaitTaskStatus(UUID taskId, String expectedStatus) {
    await()
        .atMost(Duration.ofSeconds(15))
        .untilAsserted(
            () ->
                mockMvc
                    .perform(get("/api/v1/tasks/{taskId}", taskId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(expectedStatus)));
  }

  private UUID createTripWithRequirement() throws Exception {
    MvcResult created =
        mockMvc
            .perform(
                post("/api/v1/trips")
                    .header("Idempotency-Key", "document-trip-key-0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsBytes(
                            Map.of(
                                "userId",
                                UUID.randomUUID(),
                                "title",
                                "Tokyo document trip",
                                "timeZone",
                                "Asia/Tokyo"))))
            .andExpect(status().isCreated())
            .andReturn();
    UUID tripId = UUID.fromString(read(created).get("tripId").asText());
    Map<String, Object> requirement =
        Map.ofEntries(
            Map.entry("origin", Map.of("name", "Shanghai")),
            Map.entry("destinations", List.of(Map.of("name", "Tokyo"))),
            Map.entry("startDate", "2026-10-01"),
            Map.entry("endDate", "2026-10-05"),
            Map.entry(
                "travelers",
                List.of(
                    Map.of(
                        "travelerId",
                        UUID.randomUUID(),
                        "displayName",
                        "Traveler",
                        "type",
                        "ADULT",
                        "accessibilityNeeds",
                        List.of()))),
            Map.entry("totalBudget", new BigDecimal("12000.0000")),
            Map.entry("currency", "CNY"),
            Map.entry("interests", List.of()),
            Map.entry("pace", "BALANCED"),
            Map.entry("mustVisit", List.of()),
            Map.entry("avoid", List.of()),
            Map.entry("freeTextConstraints", ""));
    mockMvc
        .perform(
            put("/api/v1/trips/{tripId}/requirements", tripId)
                .header("Idempotency-Key", "document-requirement-key-0001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(requirement)))
        .andExpect(status().isOk());
    return tripId;
  }

  private JsonNode upload(UUID tripId, String filename, String key) throws Exception {
    MockMultipartFile file = new MockMultipartFile("file", filename, "image/png", PNG);
    MvcResult result =
        mockMvc
            .perform(
                multipart("/api/v1/documents/parse")
                    .file(file)
                    .param("tripId", tripId.toString())
                    .header("Idempotency-Key", key))
            .andExpect(status().isAccepted())
            .andReturn();
    return read(result);
  }

  private BookingExtractionResult missingDateResult() {
    ExtractedBooking booking =
        new ExtractedBooking(
            BookingType.HOTEL,
            "Example Hotel",
            null,
            null,
            "Shinjuku Central Hotel",
            "1-2-3 Shinjuku, Tokyo",
            null,
            "HOTEL-NO-DATE",
            null,
            null,
            2,
            new BigDecimal("4500.0000"),
            "CNY",
            new BigDecimal("0.6200"),
            List.of("startTime", "endTime"),
            List.of(
                new FieldEvidence(
                    "hotelName",
                    new BigDecimal("0.9600"),
                    "Shinjuku Central Hotel",
                    new EvidenceRegion(
                        1,
                        new BigDecimal("0.1000"),
                        new BigDecimal("0.1000"),
                        new BigDecimal("0.5000"),
                        new BigDecimal("0.0500")))));
    return new BookingExtractionResult(
        List.of(booking),
        List.of("MOCK parser mode: deterministic fixture output"),
        new BigDecimal("0.6200"),
        "MOCK",
        "document-parser-1.0.0",
        "mock-ocr");
  }

  private JsonNode read(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsByteArray());
  }
}
