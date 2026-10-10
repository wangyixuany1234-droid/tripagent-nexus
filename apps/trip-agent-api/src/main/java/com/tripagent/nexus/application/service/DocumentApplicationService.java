package com.tripagent.nexus.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.application.document.BookingRuleValidator;
import com.tripagent.nexus.application.document.BookingRuleValidator.ValidatedBooking;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.controller.dto.ApiModels.BookingCorrectionRequest;
import com.tripagent.nexus.controller.dto.ApiModels.BookingFieldCorrection;
import com.tripagent.nexus.controller.dto.ApiModels.TaskAccepted;
import com.tripagent.nexus.domain.model.DomainEnums.BookingReviewStatus;
import com.tripagent.nexus.domain.model.DomainEnums.BookingType;
import com.tripagent.nexus.domain.model.DomainEnums.DocumentStatus;
import com.tripagent.nexus.domain.model.DomainEnums.ExtractedFieldStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.domain.model.ParsedBooking;
import com.tripagent.nexus.domain.model.ParsedBooking.CorrectionAudit;
import com.tripagent.nexus.domain.model.ParsedBooking.FieldEvidence;
import com.tripagent.nexus.domain.model.ParsedBooking.ValidationIssue;
import com.tripagent.nexus.domain.model.ParsedDocument;
import com.tripagent.nexus.guardrail.UploadPolicy;
import com.tripagent.nexus.guardrail.UploadPolicy.UploadInspection;
import com.tripagent.nexus.infrastructure.persistence.entity.BookingCorrectionEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.DocumentEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.ParsedBookingEntity;
import com.tripagent.nexus.infrastructure.persistence.entity.TravelRequirementEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.BookingCorrectionRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.DocumentRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.ParsedBookingRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelRequirementRepository;
import com.tripagent.nexus.infrastructure.storage.DocumentStorage;
import com.tripagent.nexus.integration.document.DocumentParseEvents.Requested;
import com.tripagent.nexus.integration.model.result.BookingExtractionResult;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DocumentApplicationService {

  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
  private static final TypeReference<List<FieldEvidence>> FIELD_EVIDENCE_LIST =
      new TypeReference<>() {};
  private static final TypeReference<List<ValidationIssue>> VALIDATION_ISSUE_LIST =
      new TypeReference<>() {};

  private final DocumentRepository documentRepository;
  private final ParsedBookingRepository bookingRepository;
  private final BookingCorrectionRepository correctionRepository;
  private final TravelRequirementRepository requirementRepository;
  private final TripApplicationService tripService;
  private final TaskApplicationService taskService;
  private final DocumentOutboxService outboxService;
  private final BookingRuleValidator ruleValidator;
  private final UploadPolicy uploadPolicy;
  private final DocumentStorage documentStorage;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public DocumentApplicationService(
      DocumentRepository documentRepository,
      ParsedBookingRepository bookingRepository,
      BookingCorrectionRepository correctionRepository,
      TravelRequirementRepository requirementRepository,
      TripApplicationService tripService,
      TaskApplicationService taskService,
      DocumentOutboxService outboxService,
      BookingRuleValidator ruleValidator,
      UploadPolicy uploadPolicy,
      DocumentStorage documentStorage,
      ObjectMapper objectMapper,
      Clock clock) {
    this.documentRepository = documentRepository;
    this.bookingRepository = bookingRepository;
    this.correctionRepository = correctionRepository;
    this.requirementRepository = requirementRepository;
    this.tripService = tripService;
    this.taskService = taskService;
    this.outboxService = outboxService;
    this.ruleValidator = ruleValidator;
    this.uploadPolicy = uploadPolicy;
    this.documentStorage = documentStorage;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public TaskAccepted parse(
      UUID tripId,
      String fileName,
      String claimedMediaType,
      byte[] content,
      String idempotencyKey) {
    tripService.requireTrip(tripId);
    String safeFileName = safeFileName(fileName);
    UploadInspection inspection = uploadPolicy.inspect(safeFileName, claimedMediaType, content);
    String hash = sha256(content);
    var duplicate = documentRepository.findByTripIdAndSha256(tripId, hash);
    if (duplicate.isPresent()) {
      return taskService.findAccepted(
          TaskType.DOCUMENT_PARSE, duplicate.orElseThrow().getDocumentId());
    }

    UUID documentId = UUID.randomUUID();
    String storageKey = documentStorage.store(tripId, documentId, content);
    deleteStoredFileAfterRollback(storageKey);
    Instant now = clock.instant();
    DocumentEntity document =
        documentRepository.save(
            new DocumentEntity(
                documentId,
                tripId,
                safeFileName,
                inspection.mediaType(),
                content.length,
                hash,
                storageKey,
                inspection.pageCount(),
                now));
    TaskAccepted task = taskService.create(TaskType.DOCUMENT_PARSE, documentId);
    outboxService.enqueueRequested(document, task, idempotencyKey);
    return task;
  }

  @Transactional(readOnly = true)
  public ParsedDocument get(UUID documentId) {
    DocumentEntity entity = requireDocument(documentId);
    List<ParsedBooking> bookings =
        bookingRepository.findBySourceDocumentIdOrderByCreatedAtAsc(documentId).stream()
            .map(this::toDomain)
            .toList();
    return new ParsedDocument(
        entity.getDocumentId(),
        entity.getTripId(),
        entity.getFileName(),
        entity.getMediaType(),
        entity.getSizeBytes(),
        entity.getSha256(),
        entity.getPageCount(),
        entity.getStatus(),
        bookings,
        entity.getCreatedAt(),
        entity.getUpdatedAt());
  }

  @Transactional
  public TaskAccepted retry(UUID documentId, String idempotencyKey) {
    DocumentEntity document = requireDocument(documentId);
    if (document.getStatus() != DocumentStatus.FAILED) {
      throw new ApiException(
          "DOCUMENT_NOT_RETRYABLE",
          HttpStatus.CONFLICT,
          "Only a failed document can be queued for parsing again.");
    }
    TaskAccepted previous = taskService.findAccepted(TaskType.DOCUMENT_PARSE, documentId);
    TaskAccepted retry = taskService.createRetry(previous.taskId());
    try {
      document.requeue(clock.instant());
    } catch (IllegalStateException exception) {
      throw new ApiException(
          "DOCUMENT_NOT_RETRYABLE",
          HttpStatus.CONFLICT,
          "Only a failed document can be queued for parsing again.");
    }
    documentRepository.save(document);
    outboxService.enqueueRequested(document, retry, idempotencyKey);
    return retry;
  }

  @Transactional
  public void markParsing(UUID documentId) {
    DocumentEntity document = requireDocument(documentId);
    document.markParsing(clock.instant());
    documentRepository.save(document);
  }

  @Transactional(readOnly = true)
  public byte[] readContent(UUID documentId) {
    DocumentEntity document = requireDocument(documentId);
    if (document.getStorageKey() == null) {
      byte[] legacyContent = document.getContent();
      if (legacyContent != null) {
        return legacyContent;
      }
      throw new ApiException(
          "DOCUMENT_STORAGE_READ_FAILED",
          HttpStatus.INTERNAL_SERVER_ERROR,
          "The stored document could not be read.");
    }
    return documentStorage.read(document.getStorageKey());
  }

  @Transactional
  public Completion complete(Requested request, BookingExtractionResult result) {
    DocumentEntity document = requireDocument(request.payload().documentId());
    TravelRequirementEntity requirement =
        requirementRepository.findById(document.getTripId()).orElse(null);
    ZoneId tripZone = ZoneId.of(tripService.requireTrip(document.getTripId()).getTimeZone());
    List<ParsedBookingEntity> existing =
        bookingRepository.findOtherBookingsForTrip(document.getTripId(), document.getDocumentId());
    List<ValidatedBooking> validated =
        ruleValidator.validate(result, requirement, tripZone, existing);
    bookingRepository.deleteBySourceDocumentId(document.getDocumentId());

    Instant now = clock.instant();
    List<UUID> bookingIds = new ArrayList<>();
    boolean needsReview = validated.isEmpty();
    for (ValidatedBooking item : validated) {
      UUID bookingId = UUID.randomUUID();
      bookingIds.add(bookingId);
      needsReview |= item.reviewStatus() == BookingReviewStatus.NEEDS_REVIEW;
      var extraction = item.extraction();
      bookingRepository.save(
          new ParsedBookingEntity(
              bookingId,
              extraction.type(),
              document.getDocumentId(),
              extraction.provider(),
              extraction.origin(),
              extraction.destination(),
              extraction.hotelName(),
              extraction.hotelAddress(),
              extraction.transportNumber(),
              extraction.confirmationNumber(),
              extraction.startTime(),
              extraction.endTime(),
              extraction.travelerCount(),
              extraction.amount(),
              extraction.currency(),
              extraction.confidence(),
              objectMapper.valueToTree(extraction.missingFields()),
              item.reviewStatus(),
              objectMapper.valueToTree(item.fieldEvidence()),
              objectMapper.valueToTree(item.issues()),
              now));
    }
    if (needsReview) {
      document.markNeedsReview(now);
    } else {
      document.markParsed(now);
    }
    documentRepository.save(document);
    String parseStatus = needsReview ? "NEEDS_CONFIRMATION" : "PARSED";
    outboxService.enqueueCompleted(request, result, bookingIds, parseStatus);
    return new Completion(List.copyOf(bookingIds), needsReview);
  }

  @Transactional
  public void markFailed(UUID documentId) {
    DocumentEntity document = requireDocument(documentId);
    document.markFailed(clock.instant());
    documentRepository.save(document);
  }

  @Transactional
  public ParsedBooking correct(
      UUID documentId, UUID bookingId, BookingCorrectionRequest request, String actorId) {
    DocumentEntity document = requireDocument(documentId);
    if (document.getStatus() == DocumentStatus.CANCELLED) {
      throw new ApiException(
          "DOCUMENT_CANCELLED", HttpStatus.CONFLICT, "A cancelled document cannot be corrected.");
    }
    ParsedBookingEntity booking =
        bookingRepository
            .findById(bookingId)
            .filter(entity -> entity.getSourceDocumentId().equals(documentId))
            .orElseThrow(
                () ->
                    new ApiException(
                        "BOOKING_NOT_FOUND", HttpStatus.NOT_FOUND, "Booking was not found."));
    Set<String> correctedFields = new HashSet<>();
    Instant now = clock.instant();
    for (BookingFieldCorrection correction : request.corrections()) {
      if (!correctedFields.add(correction.field())) {
        throw new ApiException(
            "BOOKING_CORRECTION_DUPLICATE_FIELD",
            HttpStatus.UNPROCESSABLE_ENTITY,
            "Each booking field may be corrected once per request.");
      }
      String originalValue = currentValue(booking, correction.field());
      applyCorrection(booking, correction);
      correctionRepository.save(
          new BookingCorrectionEntity(
              UUID.randomUUID(),
              bookingId,
              correction.field(),
              originalValue,
              correction.value(),
              actorId,
              request.reason(),
              now));
    }
    updateEvidenceAfterCorrection(booking, correctedFields);
    removeCorrectedMissingFields(booking, correctedFields);
    revalidateCorrectedBooking(document, booking);
    booking.touch(now);
    bookingRepository.save(booking);
    completeReviewTaskWhenReady(document);
    return toDomain(booking);
  }

  private void revalidateCorrectedBooking(DocumentEntity document, ParsedBookingEntity booking) {
    TravelRequirementEntity requirement =
        requirementRepository.findById(document.getTripId()).orElse(null);
    ZoneId tripZone = ZoneId.of(tripService.requireTrip(document.getTripId()).getTimeZone());
    List<ParsedBookingEntity> otherBookings =
        new ArrayList<>(
            bookingRepository.findOtherBookingsForTrip(
                document.getTripId(), document.getDocumentId()));
    bookingRepository.findBySourceDocumentIdOrderByCreatedAtAsc(document.getDocumentId()).stream()
        .filter(item -> !item.getBookingId().equals(booking.getBookingId()))
        .forEach(otherBookings::add);
    BookingExtractionResult.ExtractedBooking extraction = toExtraction(booking);
    BookingExtractionResult result =
        new BookingExtractionResult(
            List.of(extraction), List.of(), booking.getConfidence(), "MOCK", "correction", "human");
    ValidatedBooking validated =
        ruleValidator.validate(result, requirement, tripZone, otherBookings).getFirst();
    booking.setValidationIssues(objectMapper.valueToTree(validated.issues()));
    booking.setMissingFields(objectMapper.valueToTree(extraction.missingFields()));
    booking.setReviewStatus(
        validated.issues().isEmpty()
            ? BookingReviewStatus.CORRECTED
            : BookingReviewStatus.NEEDS_REVIEW);
  }

  private void completeReviewTaskWhenReady(DocumentEntity document) {
    boolean anyNeedsReview =
        bookingRepository
            .findBySourceDocumentIdOrderByCreatedAtAsc(document.getDocumentId())
            .stream()
            .anyMatch(booking -> booking.getReviewStatus() == BookingReviewStatus.NEEDS_REVIEW);
    if (anyNeedsReview) {
      document.markNeedsReview(clock.instant());
      documentRepository.save(document);
      return;
    }
    document.markParsed(clock.instant());
    documentRepository.save(document);
    TaskAccepted task = taskService.findAccepted(TaskType.DOCUMENT_PARSE, document.getDocumentId());
    if (task.status() == TaskStatus.WAITING_CONFIRMATION) {
      taskService.transition(
          task.taskId(), TaskStatus.RUNNING, "REVIEW_APPLIED", 95, "Human corrections applied");
      taskService.transition(
          task.taskId(), TaskStatus.SUCCEEDED, "COMPLETED", 100, "Document parsing completed");
    }
  }

  private void updateEvidenceAfterCorrection(
      ParsedBookingEntity booking, Set<String> correctedFields) {
    List<FieldEvidence> existing =
        objectMapper.convertValue(booking.getFieldEvidence(), FIELD_EVIDENCE_LIST);
    List<FieldEvidence> updated = new ArrayList<>();
    Set<String> represented = new HashSet<>();
    for (FieldEvidence evidence : existing) {
      represented.add(evidence.field());
      updated.add(
          correctedFields.contains(evidence.field())
              ? new FieldEvidence(
                  evidence.field(),
                  BigDecimal.ONE,
                  ExtractedFieldStatus.CORRECTED,
                  evidence.textEvidence(),
                  evidence.region())
              : evidence);
    }
    for (String field : correctedFields) {
      if (!represented.contains(field)) {
        updated.add(
            new FieldEvidence(field, BigDecimal.ONE, ExtractedFieldStatus.CORRECTED, null, null));
      }
    }
    booking.setFieldEvidence(objectMapper.valueToTree(updated));
  }

  private void removeCorrectedMissingFields(
      ParsedBookingEntity booking, Set<String> correctedFields) {
    List<String> remaining =
        objectMapper.convertValue(booking.getMissingFields(), STRING_LIST).stream()
            .filter(field -> !correctedFields.contains(field))
            .toList();
    booking.setMissingFields(objectMapper.valueToTree(remaining));
  }

  private BookingExtractionResult.ExtractedBooking toExtraction(ParsedBookingEntity booking) {
    List<BookingExtractionResult.FieldEvidence> evidence =
        objectMapper.convertValue(booking.getFieldEvidence(), FIELD_EVIDENCE_LIST).stream()
            .map(
                item ->
                    new BookingExtractionResult.FieldEvidence(
                        item.field(),
                        item.confidence(),
                        item.textEvidence(),
                        item.region() == null
                            ? null
                            : new BookingExtractionResult.EvidenceRegion(
                                item.region().page(),
                                item.region().x(),
                                item.region().y(),
                                item.region().width(),
                                item.region().height())))
            .toList();
    return new BookingExtractionResult.ExtractedBooking(
        booking.getType(),
        booking.getProvider(),
        booking.getOrigin(),
        booking.getDestination(),
        booking.getHotelName(),
        booking.getHotelAddress(),
        booking.getTransportNumber(),
        booking.getConfirmationNumber(),
        booking.getStartTime(),
        booking.getEndTime(),
        booking.getTravelerCount(),
        booking.getAmount(),
        booking.getCurrency(),
        booking.getConfidence(),
        objectMapper.convertValue(booking.getMissingFields(), STRING_LIST),
        evidence);
  }

  private static String currentValue(ParsedBookingEntity booking, String field) {
    Object value =
        switch (field) {
          case "type" -> booking.getType();
          case "provider" -> booking.getProvider();
          case "origin" -> booking.getOrigin();
          case "destination" -> booking.getDestination();
          case "hotelName" -> booking.getHotelName();
          case "hotelAddress" -> booking.getHotelAddress();
          case "transportNumber" -> booking.getTransportNumber();
          case "startTime" -> booking.getStartTime();
          case "endTime" -> booking.getEndTime();
          case "travelerCount" -> booking.getTravelerCount();
          case "amount" -> booking.getAmount();
          case "currency" -> booking.getCurrency();
          case "confirmationNumber" -> booking.getConfirmationNumber();
          default -> throw correctionInvalid();
        };
    return value == null ? "" : value.toString();
  }

  private static void applyCorrection(
      ParsedBookingEntity booking, BookingFieldCorrection correction) {
    String value = correction.value().trim();
    try {
      switch (correction.field()) {
        case "type" -> booking.setType(BookingType.valueOf(value.toUpperCase(Locale.ROOT)));
        case "provider" -> booking.setProvider(nullable(value));
        case "origin" -> booking.setOrigin(nullable(value));
        case "destination" -> booking.setDestination(nullable(value));
        case "hotelName" -> booking.setHotelName(nullable(value));
        case "hotelAddress" -> booking.setHotelAddress(nullable(value));
        case "transportNumber" -> booking.setTransportNumber(nullable(value));
        case "startTime" -> booking.setStartTime(value.isEmpty() ? null : Instant.parse(value));
        case "endTime" -> booking.setEndTime(value.isEmpty() ? null : Instant.parse(value));
        case "travelerCount" ->
            booking.setTravelerCount(value.isEmpty() ? null : Integer.valueOf(value));
        case "amount" -> booking.setAmount(value.isEmpty() ? null : new BigDecimal(value));
        case "currency" ->
            booking.setCurrency(value.isEmpty() ? null : value.toUpperCase(Locale.ROOT));
        case "confirmationNumber" -> booking.setConfirmationNumber(nullable(value));
        default -> throw correctionInvalid();
      }
      validateCorrectedValue(booking, correction.field());
    } catch (IllegalArgumentException exception) {
      throw correctionInvalid();
    }
  }

  private static void validateCorrectedValue(ParsedBookingEntity booking, String field) {
    if ("travelerCount".equals(field)
        && booking.getTravelerCount() != null
        && (booking.getTravelerCount() < 1 || booking.getTravelerCount() > 100)) {
      throw correctionInvalid();
    }
    if ("amount".equals(field) && booking.getAmount() != null && booking.getAmount().signum() < 0) {
      throw correctionInvalid();
    }
    if ("currency".equals(field)
        && booking.getCurrency() != null
        && !booking.getCurrency().matches("^[A-Z]{3}$")) {
      throw correctionInvalid();
    }
  }

  private ParsedBooking toDomain(ParsedBookingEntity entity) {
    List<CorrectionAudit> corrections =
        correctionRepository.findByBookingIdOrderByCorrectedAtAsc(entity.getBookingId()).stream()
            .map(
                audit ->
                    new CorrectionAudit(
                        audit.getAuditId(),
                        audit.getField(),
                        audit.getOriginalValue(),
                        audit.getCorrectedValue(),
                        audit.getActorId(),
                        audit.getReason(),
                        audit.getCorrectedAt()))
            .toList();
    return new ParsedBooking(
        entity.getBookingId(),
        entity.getType(),
        entity.getSourceDocumentId(),
        entity.getProvider(),
        entity.getOrigin(),
        entity.getDestination(),
        entity.getHotelName(),
        entity.getHotelAddress(),
        entity.getTransportNumber(),
        entity.getConfirmationNumber(),
        entity.getStartTime(),
        entity.getEndTime(),
        entity.getTravelerCount(),
        entity.getAmount(),
        entity.getCurrency(),
        entity.getConfidence(),
        objectMapper.convertValue(entity.getMissingFields(), STRING_LIST),
        entity.getReviewStatus(),
        objectMapper.convertValue(entity.getFieldEvidence(), FIELD_EVIDENCE_LIST),
        objectMapper.convertValue(entity.getValidationIssues(), VALIDATION_ISSUE_LIST),
        corrections);
  }

  private DocumentEntity requireDocument(UUID documentId) {
    return documentRepository
        .findById(documentId)
        .orElseThrow(
            () ->
                new ApiException(
                    "DOCUMENT_NOT_FOUND", HttpStatus.NOT_FOUND, "Document was not found."));
  }

  private static String safeFileName(String fileName) {
    String candidate = fileName == null ? "" : fileName.replace('\\', '/');
    candidate = candidate.substring(candidate.lastIndexOf('/') + 1).trim();
    if (candidate.isEmpty() || candidate.length() > 255 || candidate.indexOf('\0') >= 0) {
      throw new ApiException(
          "DOCUMENT_FILENAME_INVALID",
          HttpStatus.BAD_REQUEST,
          "The document file name is invalid.");
    }
    return candidate;
  }

  private static String nullable(String value) {
    return value.isBlank() ? null : value;
  }

  private static ApiException correctionInvalid() {
    return new ApiException(
        "BOOKING_CORRECTION_INVALID",
        HttpStatus.UNPROCESSABLE_ENTITY,
        "A corrected booking field has an invalid value.");
  }

  private void deleteStoredFileAfterRollback(String storageKey) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == STATUS_ROLLED_BACK) {
              documentStorage.deleteQuietly(storageKey);
            }
          }
        });
  }

  public static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record Completion(List<UUID> bookingIds, boolean needsReview) {
    public Completion {
      bookingIds = List.copyOf(Objects.requireNonNull(bookingIds));
    }
  }
}
