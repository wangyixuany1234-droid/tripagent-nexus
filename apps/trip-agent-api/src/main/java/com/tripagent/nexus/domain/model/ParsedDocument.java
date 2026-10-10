package com.tripagent.nexus.domain.model;

import com.tripagent.nexus.domain.model.DomainEnums.DocumentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ParsedDocument(
    UUID documentId,
    UUID tripId,
    String fileName,
    String mediaType,
    long sizeBytes,
    String sha256,
    int pageCount,
    DocumentStatus status,
    List<ParsedBooking> bookings,
    Instant createdAt,
    Instant updatedAt) {}
