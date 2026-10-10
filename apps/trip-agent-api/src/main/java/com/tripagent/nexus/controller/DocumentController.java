package com.tripagent.nexus.controller;

import com.tripagent.nexus.application.idempotency.IdempotencyService;
import com.tripagent.nexus.application.security.AuthenticatedActorService;
import com.tripagent.nexus.application.security.ResourceAuthorizationService;
import com.tripagent.nexus.application.service.DocumentApplicationService;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.controller.dto.ApiModels.BookingCorrectionRequest;
import com.tripagent.nexus.controller.dto.ApiModels.TaskAccepted;
import com.tripagent.nexus.domain.model.ParsedBooking;
import com.tripagent.nexus.domain.model.ParsedDocument;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/documents")
@Validated
public class DocumentController {

  private final DocumentApplicationService documentService;
  private final IdempotencyService idempotencyService;
  private final ResourceAuthorizationService authorization;
  private final AuthenticatedActorService actors;

  public DocumentController(
      DocumentApplicationService documentService,
      IdempotencyService idempotencyService,
      ResourceAuthorizationService authorization,
      AuthenticatedActorService actors) {
    this.documentService = documentService;
    this.idempotencyService = idempotencyService;
    this.authorization = authorization;
    this.actors = actors;
  }

  @PostMapping(path = "/parse", consumes = "multipart/form-data")
  public ResponseEntity<TaskAccepted> parse(
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      Authentication authentication,
      @RequestParam UUID tripId,
      @RequestParam MultipartFile file) {
    authorization.requireTrip(authentication, tripId);
    byte[] content = read(file);
    String fileName = file.getOriginalFilename();
    String mediaType = file.getContentType();
    DocumentFingerprint fingerprint =
        new DocumentFingerprint(
            tripId,
            fileName,
            mediaType,
            content.length,
            DocumentApplicationService.sha256(content));
    TaskAccepted accepted =
        idempotencyService.execute(
            idempotencyKey,
            "POST:/api/v1/documents/parse",
            fingerprint,
            TaskAccepted.class,
            () -> documentService.parse(tripId, fileName, mediaType, content, idempotencyKey));
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/documents/" + accepted.resourceId()))
        .body(accepted);
  }

  @GetMapping("/{documentId}")
  public ParsedDocument get(@PathVariable UUID documentId, Authentication authentication) {
    authorization.requireDocument(authentication, documentId);
    return documentService.get(documentId);
  }

  @PostMapping("/{documentId}/retry")
  public ResponseEntity<TaskAccepted> retry(
      @PathVariable UUID documentId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      Authentication authentication) {
    authorization.requireDocument(authentication, documentId);
    TaskAccepted accepted =
        idempotencyService.execute(
            idempotencyKey,
            "POST:/api/v1/documents/{documentId}/retry:" + documentId,
            new DocumentRetryFingerprint(documentId),
            TaskAccepted.class,
            () -> documentService.retry(documentId, idempotencyKey));
    return ResponseEntity.accepted()
        .location(URI.create("/api/v1/documents/" + accepted.resourceId()))
        .body(accepted);
  }

  @PatchMapping("/{documentId}/bookings/{bookingId}")
  public ParsedBooking correct(
      @PathVariable UUID documentId,
      @PathVariable UUID bookingId,
      @RequestHeader("Idempotency-Key") String idempotencyKey,
      @RequestHeader(value = "X-Actor-Id", required = false)
          @Pattern(regexp = "^[A-Za-z0-9._:@-]{1,128}$") String testActorId,
      Authentication authentication,
      @Valid @RequestBody BookingCorrectionRequest request) {
    authorization.requireDocument(authentication, documentId);
    String actorId = actors.actorId(authentication, testActorId);
    return idempotencyService.execute(
        idempotencyKey,
        "PATCH:/api/v1/documents/{documentId}/bookings/{bookingId}:" + bookingId,
        new BookingCorrectionFingerprint(documentId, bookingId, actorId, request),
        ParsedBooking.class,
        () -> documentService.correct(documentId, bookingId, request, actorId));
  }

  private static byte[] read(MultipartFile file) {
    try {
      return file.getBytes();
    } catch (IOException exception) {
      throw new ApiException(
          "DOCUMENT_READ_FAILED",
          HttpStatus.BAD_REQUEST,
          "The uploaded document could not be read.");
    }
  }

  private record DocumentFingerprint(
      UUID tripId, String fileName, String mediaType, long sizeBytes, String sha256) {}

  private record DocumentRetryFingerprint(UUID documentId) {}

  private record BookingCorrectionFingerprint(
      UUID documentId, UUID bookingId, String actorId, BookingCorrectionRequest request) {}
}
