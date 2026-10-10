package com.tripagent.nexus.domain.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.UUID;

public record Place(
    @JsonInclude(JsonInclude.Include.NON_NULL) UUID placeId,
    String name,
    @JsonInclude(JsonInclude.Include.NON_NULL) String address,
    @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal latitude,
    @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal longitude) {}
