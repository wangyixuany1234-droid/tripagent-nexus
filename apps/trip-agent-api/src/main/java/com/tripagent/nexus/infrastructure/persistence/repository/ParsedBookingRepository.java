package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.infrastructure.persistence.entity.ParsedBookingEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParsedBookingRepository extends JpaRepository<ParsedBookingEntity, UUID> {

  List<ParsedBookingEntity> findBySourceDocumentIdOrderByCreatedAtAsc(UUID sourceDocumentId);

  void deleteBySourceDocumentId(UUID sourceDocumentId);

  @Query(
      """
      select booking
      from ParsedBookingEntity booking
      join DocumentEntity document on document.documentId = booking.sourceDocumentId
      where document.tripId = :tripId
        and booking.sourceDocumentId <> :documentId
      """)
  List<ParsedBookingEntity> findOtherBookingsForTrip(
      @Param("tripId") UUID tripId, @Param("documentId") UUID documentId);

  @Query(
      """
      select booking
      from ParsedBookingEntity booking
      join DocumentEntity document on document.documentId = booking.sourceDocumentId
      where document.tripId = :tripId
      order by booking.createdAt
      """)
  List<ParsedBookingEntity> findByTripId(@Param("tripId") UUID tripId);
}
