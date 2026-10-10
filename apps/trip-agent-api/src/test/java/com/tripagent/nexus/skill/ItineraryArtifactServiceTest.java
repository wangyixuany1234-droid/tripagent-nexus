package com.tripagent.nexus.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tripagent.nexus.infrastructure.persistence.entity.ItineraryArtifactEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.ItineraryArtifactRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TripRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ItineraryArtifactServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-04T12:00:00Z");

  private final ItineraryArtifactRepository repository = mock(ItineraryArtifactRepository.class);
  private final TripRepository tripRepository = mock(TripRepository.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final ItineraryArtifactService service =
      new ItineraryArtifactService(
          repository, tripRepository, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void returnsOriginalArtifactForAnIdempotentReplay() {
    UUID tripId = UUID.randomUUID();
    AtomicReference<ItineraryArtifactEntity> stored = configureRepository(tripId);
    ObjectNode itinerary = objectMapper.createObjectNode().put("title", "Beijing");

    var first = service.store(tripId, 3, "store-key", itinerary);
    var replay = service.store(tripId, 3, "store-key", itinerary.deepCopy());

    assertThat(replay).isEqualTo(first);
    assertThat(stored.get().getCreatedAt()).isEqualTo(NOW);
  }

  @Test
  void rejectsAnIdempotencyKeyReusedForDifferentContent() {
    UUID tripId = UUID.randomUUID();
    configureRepository(tripId);
    service.store(tripId, 3, "store-key", objectMapper.createObjectNode().put("title", "Beijing"));

    assertThatThrownBy(
            () ->
                service.store(
                    tripId,
                    3,
                    "store-key",
                    objectMapper.createObjectNode().put("title", "Shanghai")))
        .isInstanceOf(ItineraryArtifactService.McpToolException.class)
        .satisfies(
            exception ->
                assertThat(((ItineraryArtifactService.McpToolException) exception).code())
                    .isEqualTo("ITINERARY_IDEMPOTENCY_CONFLICT"));
  }

  private AtomicReference<ItineraryArtifactEntity> configureRepository(UUID tripId) {
    AtomicReference<ItineraryArtifactEntity> stored = new AtomicReference<>();
    when(tripRepository.existsById(tripId)).thenReturn(true);
    when(repository.findByIdempotencyKey("store-key"))
        .thenAnswer(ignored -> Optional.ofNullable(stored.get()));
    when(repository.save(any(ItineraryArtifactEntity.class)))
        .thenAnswer(
            invocation -> {
              ItineraryArtifactEntity entity = invocation.getArgument(0);
              stored.set(entity);
              return entity;
            });
    return stored;
  }
}
