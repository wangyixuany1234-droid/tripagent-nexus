package com.tripagent.nexus.integration.document;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.domain.model.DomainEnums.BookingType;
import com.tripagent.nexus.infrastructure.persistence.entity.DocumentEntity;
import com.tripagent.nexus.integration.messaging.AsyncOperationException;
import com.tripagent.nexus.integration.messaging.ExternalCallExecutor;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult.EvidenceRegion;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult.ExtractedBooking;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult.FieldEvidence;
import jakarta.validation.Validator;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class AiDocumentParserClient {

  private static final Logger log = LoggerFactory.getLogger(AiDocumentParserClient.class);
  private static final Pattern DECIMAL_STRING = Pattern.compile("^\\d+(?:\\.\\d{1,4})?$");

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final Validator validator;
  private final ExternalCallExecutor callExecutor;

  public AiDocumentParserClient(
      @Qualifier("aiDocumentRestClient") RestClient restClient,
      ObjectMapper objectMapper,
      Validator validator,
      ExternalCallExecutor callExecutor) {
    this.restClient = restClient;
    this.objectMapper = objectMapper;
    this.validator = validator;
    this.callExecutor = callExecutor;
  }

  public BookingExtractionResult parse(
      DocumentEntity document, UUID taskId, byte[] content, String locale, String traceId) {
    MultipartBodyBuilder body = new MultipartBodyBuilder();
    body.part("documentId", document.getDocumentId().toString());
    body.part("taskId", taskId.toString());
    body.part("mediaType", document.getMediaType());
    body.part("sha256", document.getSha256());
    body.part("locale", locale);
    body.part(
            "file",
            new ByteArrayResource(content) {
              @Override
              public String getFilename() {
                return document.getFileName();
              }
            })
        .contentType(MediaType.parseMediaType(document.getMediaType()));

    return callExecutor.execute(
        "document-parser",
        1,
        () -> {
          try {
            byte[] response =
                restClient
                    .post()
                    .uri("/internal/v1/documents/parse")
                    .header("traceparent", traceParent(traceId))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(asMap(body))
                    .retrieve()
                    .body(byte[].class);
            return decode(response);
          } catch (RestClientException exception) {
            log.warn(
                "Document parser request failed errorType={} rootCauseType={}",
                exception.getClass().getSimpleName(),
                rootCauseType(exception));
            throw parserFailure(exception);
          }
        });
  }

  private BookingExtractionResult decode(byte[] response) {
    if (response == null || response.length == 0) {
      log.warn("Document parser returned an empty response");
      throw invalidResponse(null);
    }
    try {
      ParserResponse decoded = objectMapper.readValue(response, ParserResponse.class);
      if (decoded == null) {
        throw invalidResponse(null);
      }
      BookingExtractionResult result = decoded.toResult();
      if (result == null || !validator.validate(result).isEmpty()) {
        log.warn("Document parser response failed validation responseBytes={}", response.length);
        throw invalidResponse(null);
      }
      return result;
    } catch (IOException | IllegalArgumentException exception) {
      log.warn(
          "Document parser response could not be decoded responseBytes={} errorType={} path={}",
          response.length,
          exception.getClass().getSimpleName(),
          mappingPath(exception));
      throw invalidResponse(exception);
    }
  }

  private static MultiValueMap<String, org.springframework.http.HttpEntity<?>> asMap(
      MultipartBodyBuilder body) {
    return body.build();
  }

  private static AsyncOperationException parserFailure(RestClientException cause) {
    return new AsyncOperationException(
        "DOCUMENT_PARSER_UNAVAILABLE",
        true,
        "The document parser is temporarily unavailable; retry will be attempted.",
        cause);
  }

  private static AsyncOperationException invalidResponse(Throwable cause) {
    return new AsyncOperationException(
        "DOCUMENT_PARSER_RESPONSE_INVALID",
        false,
        "The document parser returned an invalid response.",
        cause);
  }

  private static String mappingPath(Throwable exception) {
    if (!(exception instanceof JsonMappingException mappingException)
        || mappingException.getPath().isEmpty()) {
      return "<root>";
    }
    return mappingException.getPath().stream()
        .map(
            reference ->
                reference.getFieldName() == null
                    ? "[" + reference.getIndex() + "]"
                    : reference.getFieldName())
        .collect(Collectors.joining("."));
  }

  private static BigDecimal requiredDecimal(String value) {
    if (value == null || !DECIMAL_STRING.matcher(value).matches()) {
      throw new IllegalArgumentException("Invalid decimal string");
    }
    return new BigDecimal(value);
  }

  private static BigDecimal optionalDecimal(String value) {
    return value == null ? null : requiredDecimal(value);
  }

  private record ParserResponse(
      List<ParserBooking> bookings,
      List<String> warnings,
      String confidence,
      String parserMode,
      String parserVersion,
      String ocrEngine) {

    private BookingExtractionResult toResult() {
      requireElements(bookings);
      return new BookingExtractionResult(
          bookings.stream().map(ParserBooking::toResult).toList(),
          warnings,
          requiredDecimal(confidence),
          parserMode,
          parserVersion,
          ocrEngine);
    }
  }

  private record ParserBooking(
      BookingType type,
      String provider,
      String origin,
      String destination,
      String hotelName,
      String hotelAddress,
      String transportNumber,
      String confirmationNumber,
      Instant startTime,
      Instant endTime,
      Integer travelerCount,
      String amount,
      String currency,
      String confidence,
      List<String> missingFields,
      List<ParserFieldEvidence> fieldEvidence) {

    private ExtractedBooking toResult() {
      requireElements(fieldEvidence);
      return new ExtractedBooking(
          type,
          provider,
          origin,
          destination,
          hotelName,
          hotelAddress,
          transportNumber,
          confirmationNumber,
          startTime,
          endTime,
          travelerCount,
          optionalDecimal(amount),
          currency,
          requiredDecimal(confidence),
          missingFields,
          fieldEvidence.stream().map(ParserFieldEvidence::toResult).toList());
    }
  }

  private record ParserFieldEvidence(
      String field, String confidence, String textEvidence, ParserEvidenceRegion region) {

    private FieldEvidence toResult() {
      return new FieldEvidence(
          field,
          requiredDecimal(confidence),
          textEvidence,
          region == null ? null : region.toResult());
    }
  }

  private static void requireElements(List<?> values) {
    if (values == null || values.stream().anyMatch(java.util.Objects::isNull)) {
      throw new IllegalArgumentException("Required response collection is missing");
    }
  }

  private record ParserEvidenceRegion(int page, String x, String y, String width, String height) {

    private EvidenceRegion toResult() {
      return new EvidenceRegion(
          page,
          requiredDecimal(x),
          requiredDecimal(y),
          requiredDecimal(width),
          requiredDecimal(height));
    }
  }

  private static String rootCauseType(Throwable exception) {
    Throwable current = exception;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current.getClass().getSimpleName();
  }

  private static String traceParent(String traceId) {
    String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "00-" + traceId + "-" + spanId + "-01";
  }
}
