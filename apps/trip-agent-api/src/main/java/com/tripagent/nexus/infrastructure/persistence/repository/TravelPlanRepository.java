package com.tripagent.nexus.infrastructure.persistence.repository;

import com.tripagent.nexus.infrastructure.persistence.entity.TravelPlanEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TravelPlanRepository extends JpaRepository<TravelPlanEntity, UUID> {
  List<TravelPlanEntity> findByTripIdOrderByCreatedAtDesc(UUID tripId);

  List<TravelPlanEntity> findByPlanFamilyIdOrderByVersionDesc(UUID planFamilyId);

  @Query("select plan.tripId from TravelPlanEntity plan where plan.planId = :planId")
  Optional<UUID> findTripIdByPlanId(@Param("planId") UUID planId);

  @Query(
      "select max(plan.version) from TravelPlanEntity plan where plan.planFamilyId = :planFamilyId")
  Optional<Integer> findMaxVersionByPlanFamilyId(@Param("planFamilyId") UUID planFamilyId);
}
