package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.infrastructure.persistence.entity.BookingCorrectionEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingCorrectionRepository extends JpaRepository<BookingCorrectionEntity, UUID> {

  List<BookingCorrectionEntity> findByBookingIdOrderByCorrectedAtAsc(UUID bookingId);
}
