package com.tripagent.nexus.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tripagent.nexus.domain.model.DomainEnums.EvidenceSourceType;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public record Evidence(
    UUID evidenceId,
    EvidenceSourceType sourceType,
    String sourceId,
    String title,
    String excerpt,
    @JsonInclude(JsonInclude.Include.NON_NULL) URI url,
    Instant retrievedAt) {}
