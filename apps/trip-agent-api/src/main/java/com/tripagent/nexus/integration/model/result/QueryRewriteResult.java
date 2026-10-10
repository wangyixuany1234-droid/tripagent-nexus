package com.tripagent.nexus.integration.model.result;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

public record QueryRewriteResult(
    @NotBlank String originalQuery,
    @NotBlank String rewrittenQuery,
    @NotEmpty List<@NotBlank String> keywords,
    @NotNull List<@NotBlank String> graphEntities,
    @NotNull Map<@NotBlank String, @NotBlank String> filters) {}
