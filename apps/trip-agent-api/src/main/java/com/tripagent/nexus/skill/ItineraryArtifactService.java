package com.tripagent.nexus.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.infrastructure.persistence.entity.ItineraryArtifactEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.ItineraryArtifactRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TripRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ItineraryArtifactService {

  private final ItineraryArtifactRepository repository;
  private final TripRepository tripRepository;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public ItineraryArtifactService(
      ItineraryArtifactRepository repository,
      TripRepository tripRepository,
      ObjectMapper objectMapper,
      Clock clock) {
    this.repository = repository;
    this.tripRepository = tripRepository;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Transactional
  public StoredItinerary store(
      UUID tripId, int planVersion, String idempotencyKey, JsonNode itinerary) {
    if (!tripRepository.existsById(tripId)) {
      throw new McpToolException("ITINERARY_TRIP_NOT_FOUND", "Trip was not found.");
    }
    String requestHash = hash(tripId, planVersion, itinerary);
    var existing = repository.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
      ItineraryArtifactEntity entity = existing.orElseThrow();
      if (!entity.getRequestHash().equals(requestHash)) {
        throw new McpToolException(
            "ITINERARY_IDEMPOTENCY_CONFLICT",
            "The idempotency key was already used for a different itinerary.");
      }
      return new StoredItinerary(
          entity.getItineraryArtifactId(),
          entity.getTripId(),
          entity.getPlanVersion(),
          entity.getCreatedAt());
    }

    ItineraryArtifactEntity entity =
        repository.save(
            new ItineraryArtifactEntity(
                UUID.randomUUID(),
                tripId,
                planVersion,
                idempotencyKey,
                requestHash,
                itinerary,
                clock.instant()));
    return new StoredItinerary(
        entity.getItineraryArtifactId(),
        entity.getTripId(),
        entity.getPlanVersion(),
        entity.getCreatedAt());
  }

  private String hash(UUID tripId, int planVersion, JsonNode itinerary) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(tripId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
      digest.update((byte) ':');
      digest.update(
          Integer.toString(planVersion).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      digest.update((byte) ':');
      digest.update(objectMapper.writeValueAsBytes(itinerary));
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
      throw new IllegalStateException("Itinerary request could not be hashed.", exception);
    }
  }

  public record StoredItinerary(
      UUID itineraryArtifactId, UUID tripId, int planVersion, java.time.Instant createdAt) {}

  public static class McpToolException extends RuntimeException {
    private final String code;

    public McpToolException(String code, String message) {
      super(message);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
