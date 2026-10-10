package com.tripagent.nexus.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OpenApiContractTest {

  private static OpenAPI openApi;
  private static SwaggerParseResult parseResult;

  @BeforeAll
  static void parseContract() {
    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    options.setResolveFully(true);
    parseResult = new OpenAPIV3Parser().readLocation(findContract().toString(), null, options);
    openApi = parseResult.getOpenAPI();
  }

  @Test
  void contractIsValidOpenApi31() {
    assertThat(openApi).isNotNull();
    assertThat(openApi.getOpenapi()).isEqualTo("3.1.0");
    assertThat(openApi.getInfo().getVersion()).isEqualTo("1.0.0");
    assertThat(openApi.getExtensions())
        .containsEntry("x-api-stability", "frozen")
        .containsEntry("x-api-version", "v1");
    assertThat(parseResult.getMessages()).isEmpty();
  }

  @Test
  void frozenV1ContractAllowsOnlyBackwardCompatibleFieldGrowth() {
    OpenAPI frozen = parse(findFrozenContract());
    assertThat(frozen.getInfo().getVersion()).isEqualTo("1.0.0");
    assertThat(openApi.getPaths().keySet()).allMatch(path -> path.startsWith("/api/v1"));

    frozen
        .getPaths()
        .forEach(
            (path, frozenPath) -> {
              PathItem currentPath = openApi.getPaths().get(path);
              assertThat(currentPath).as("frozen path %s", path).isNotNull();
              frozenPath
                  .readOperationsMap()
                  .forEach(
                      (method, ignored) ->
                          assertThat(currentPath.readOperationsMap())
                              .as("frozen operation %s %s", method, path)
                              .containsKey(method));
            });

    frozen
        .getComponents()
        .getSchemas()
        .forEach(
            (name, frozenSchema) -> {
              Schema<?> currentSchema = openApi.getComponents().getSchemas().get(name);
              assertThat(currentSchema).as("frozen schema %s", name).isNotNull();
              assertSchemaPropertiesRemainCompatible(name, frozenSchema, currentSchema);
            });
  }

  @Test
  void exposesExactlyTheCurrentPublicOperations() {
    Set<String> expectedPaths =
        Set.of(
            "/api/v1/health",
            "/api/v1/system/status",
            "/api/v1/maps/tiles/{zoom}/{x}/{y}.png",
            "/api/v1/trips",
            "/api/v1/trips/{tripId}",
            "/api/v1/trips/{tripId}/requirements",
            "/api/v1/documents/parse",
            "/api/v1/documents/{documentId}",
            "/api/v1/documents/{documentId}/retry",
            "/api/v1/documents/{documentId}/bookings/{bookingId}",
            "/api/v1/plans",
            "/api/v1/plans/{planId}",
            "/api/v1/trips/{tripId}/plans",
            "/api/v1/plans/{planId}/replan",
            "/api/v1/plans/{planId}/compare/{otherPlanId}",
            "/api/v1/plans/{planId}/confirm",
            "/api/v1/feedback",
            "/api/v1/workbench/tasks",
            "/api/v1/users/{userId}/preferences",
            "/api/v1/users/{userId}/preferences/{preferenceId}",
            "/api/v1/users/{userId}/preferences/{preferenceId}/reject",
            "/api/v1/tasks/{taskId}",
            "/api/v1/tasks/{taskId}/events",
            "/api/v1/tasks/{taskId}/agents",
            "/api/v1/tasks/{taskId}/cancel",
            "/api/v1/developer/traces/{traceId}",
            "/api/v1/operations/dead-letters",
            "/api/v1/operations/dead-letters/{deadLetterId}/replay",
            "/api/v1/actions",
            "/api/v1/actions/{actionId}",
            "/api/v1/actions/{actionId}/confirm",
            "/api/v1/actions/{actionId}/reject",
            "/api/v1/evaluations/runs",
            "/api/v1/evaluations/runs/{runId}");

    assertThat(openApi.getPaths().keySet()).isEqualTo(expectedPaths);
    assertThat(openApi.getPaths().get("/api/v1/documents/parse").getPost().getResponses())
        .containsKey("202");
    assertThat(openApi.getPaths().get("/api/v1/plans").getPost().getResponses()).containsKey("202");
    assertThat(openApi.getPaths().get("/api/v1/plans/{planId}/replan").getPost().getResponses())
        .containsKey("202");
    assertThat(openApi.getComponents().getSchemas().get("ReplanRequest").getProperties().keySet())
        .contains(
            "lockedItemIds",
            "removedItemIds",
            "adjustedTotalBudget",
            "adjustedPace",
            "addedPreferences",
            "naturalLanguageRequest");
    assertThat(openApi.getComponents().getSchemas().get("TaskRecord").getProperties().keySet())
        .contains(
            "failureCode",
            "failureReason",
            "retryable",
            "recoveryAttempts",
            "degradedComponents",
            "retryOfTaskId");
    assertThat(openApi.getComponents().getSchemas().get("GuardedAction").getProperties().keySet())
        .contains("actionId", "riskDescription", "amount", "expiresAt", "version", "auditTrail");
    assertThat(
            openApi.getComponents().getSchemas().get("EvaluationMetrics").getProperties().keySet())
        .containsExactlyInAnyOrder(
            "itineraryExecutabilityRate",
            "constraintConflictRate",
            "evidenceCoverageRate",
            "toolCallSuccessRate",
            "structuredOutputValidityRate",
            "replanSuccessRate",
            "endToEndLatencyMs",
            "tokenUsage");
  }

  @Test
  void everyWriteOperationRequiresIdempotencyKey() {
    for (Map.Entry<String, PathItem> entry : openApi.getPaths().entrySet()) {
      assertIdempotency(entry.getKey(), entry.getValue().getPost());
      assertIdempotency(entry.getKey(), entry.getValue().getPut());
      assertIdempotency(entry.getKey(), entry.getValue().getPatch());
      assertIdempotency(entry.getKey(), entry.getValue().getDelete());
    }
  }

  @Test
  void taskStatusUsesTheSingleDeclaredStateSet() {
    List<?> statuses = openApi.getComponents().getSchemas().get("TaskStatus").getEnum();
    assertThat(statuses.stream().map(String::valueOf).toList())
        .containsExactly(
            "CREATED",
            "QUEUED",
            "RUNNING",
            "WAITING_CONFIRMATION",
            "SUCCEEDED",
            "PARTIAL_SUCCESS",
            "FAILED",
            "CANCELLED");
  }

  @Test
  void internalAiContractIsValid() {
    SwaggerParseResult internal =
        new OpenAPIV3Parser()
            .readLocation(findInternalContract().toString(), null, new ParseOptions());
    assertThat(internal.getOpenAPI()).isNotNull();
    assertThat(internal.getMessages()).isEmpty();
    assertThat(internal.getOpenAPI().getPaths().keySet())
        .containsExactlyInAnyOrder(
            "/internal/v1/health",
            "/internal/v1/runtime-metrics",
            "/internal/v1/documents/parse",
            "/internal/v1/knowledge/ingest",
            "/internal/v1/memory/ingest",
            "/internal/v1/memory/{preferenceId}",
            "/internal/v1/retrieval/search",
            "/internal/v1/graph/search");
    assertIdempotency(
        "/internal/v1/knowledge/ingest",
        internal.getOpenAPI().getPaths().get("/internal/v1/knowledge/ingest").getPost());
    assertIdempotency(
        "/internal/v1/memory/ingest",
        internal.getOpenAPI().getPaths().get("/internal/v1/memory/ingest").getPost());
  }

  private static void assertIdempotency(String path, Operation operation) {
    if (operation == null) {
      return;
    }
    assertThat(operation.getParameters())
        .as("%s %s", operation.getOperationId(), path)
        .anySatisfy(
            parameter ->
                assertThat(parameter.getName() != null ? parameter.getName() : parameter.get$ref())
                    .contains("Idempotency"));
  }

  private static Path findContract() {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      Path candidate = directory.resolve("packages/api-contract/openapi.yaml");
      if (Files.exists(candidate)) {
        return candidate;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException("Could not locate OpenAPI contract");
  }

  private static Path findInternalContract() {
    return findContract().resolveSibling("ai-internal-openapi.yaml");
  }

  private static Path findFrozenContract() {
    return findContract()
        .getParent()
        .getParent()
        .getParent()
        .resolve("contracts/openapi/tripagent-v1.yaml");
  }

  private static OpenAPI parse(Path contract) {
    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    options.setResolveFully(true);
    SwaggerParseResult result =
        new OpenAPIV3Parser().readLocation(contract.toString(), null, options);
    assertThat(result.getMessages()).as("parse messages for %s", contract).isEmpty();
    return result.getOpenAPI();
  }

  private static void assertSchemaPropertiesRemainCompatible(
      String schemaName, Schema<?> frozenSchema, Schema<?> currentSchema) {
    Map<String, Schema> frozenProperties = frozenSchema.getProperties();
    if (frozenProperties == null || frozenProperties.isEmpty()) {
      return;
    }
    Map<String, Schema> currentProperties = currentSchema.getProperties();
    assertThat(currentProperties).as("properties of %s", schemaName).isNotNull();
    assertThat(currentProperties.keySet())
        .as("frozen fields of %s", schemaName)
        .containsAll(frozenProperties.keySet());

    Set<String> addedFields = new HashSet<>(currentProperties.keySet());
    addedFields.removeAll(frozenProperties.keySet());
    if (!addedFields.isEmpty()) {
      assertThat(currentSchema.getRequired() == null ? Set.of() : currentSchema.getRequired())
          .as("new fields of %s must remain optional", schemaName)
          .doesNotContainAnyElementsOf(addedFields);
    }

    frozenProperties.forEach(
        (field, frozenField) -> {
          Schema<?> currentField = currentProperties.get(field);
          assertThat(currentField.getType())
              .as("type of %s.%s", schemaName, field)
              .isEqualTo(frozenField.getType());
          assertThat(currentField.getFormat())
              .as("format of %s.%s", schemaName, field)
              .isEqualTo(frozenField.getFormat());
          assertThat(currentField.get$ref())
              .as("reference of %s.%s", schemaName, field)
              .isEqualTo(frozenField.get$ref());
        });
  }
}
