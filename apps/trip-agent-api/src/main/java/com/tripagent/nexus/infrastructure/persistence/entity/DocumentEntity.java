package com.tripagent.nexus.infrastructure.persistence.entity;

import com.tripagent.nexus.domain.model.DomainEnums.DocumentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "uploaded_document")
public class DocumentEntity {

  @Id
  @Column(name = "document_id", nullable = false)
  private UUID documentId;

  @Column(name = "trip_id", nullable = false)
  private UUID tripId;

  @Column(name = "file_name", nullable = false, length = 255)
  private String fileName;

  @Column(name = "media_type", nullable = false, length = 128)
  private String mediaType;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(nullable = false, length = 64)
  private String sha256;

  @Column(columnDefinition = "bytea")
  private byte[] content;

  @Column(name = "storage_key", length = 500)
  private String storageKey;

  @Column(name = "page_count", nullable = false)
  private int pageCount;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private DocumentStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected DocumentEntity() {}

  public DocumentEntity(
      UUID documentId,
      UUID tripId,
      String fileName,
      String mediaType,
      long sizeBytes,
      String sha256,
      String storageKey,
      int pageCount,
      Instant now) {
    this.documentId = documentId;
    this.tripId = tripId;
    this.fileName = fileName;
    this.mediaType = mediaType;
    this.sizeBytes = sizeBytes;
    this.sha256 = sha256;
    this.storageKey = storageKey;
    this.pageCount = pageCount;
    this.status = DocumentStatus.QUEUED;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void cancel(Instant now) {
    this.status = DocumentStatus.CANCELLED;
    this.updatedAt = now;
  }

  public void markParsing(Instant now) {
    this.status = DocumentStatus.PARSING;
    this.updatedAt = now;
  }

  public void markNeedsReview(Instant now) {
    this.status = DocumentStatus.NEEDS_REVIEW;
    this.updatedAt = now;
  }

  public void markParsed(Instant now) {
    this.status = DocumentStatus.PARSED;
    this.updatedAt = now;
  }

  public void markFailed(Instant now) {
    this.status = DocumentStatus.FAILED;
    this.updatedAt = now;
  }

  public void requeue(Instant now) {
    if (status != DocumentStatus.FAILED) {
      throw new IllegalStateException("DOCUMENT_NOT_RETRYABLE");
    }
    this.status = DocumentStatus.QUEUED;
    this.updatedAt = now;
  }

  public UUID getDocumentId() {
    return documentId;
  }

  public UUID getTripId() {
    return tripId;
  }

  public String getFileName() {
    return fileName;
  }

  public String getMediaType() {
    return mediaType;
  }

  public long getSizeBytes() {
    return sizeBytes;
  }

  public String getSha256() {
    return sha256;
  }

  public String getStorageKey() {
    return storageKey;
  }

  public byte[] getContent() {
    return content == null ? null : content.clone();
  }

  public int getPageCount() {
    return pageCount;
  }

  public DocumentStatus getStatus() {
    return status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
