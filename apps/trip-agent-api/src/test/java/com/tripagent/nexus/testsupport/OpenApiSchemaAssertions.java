package com.tripagent.nexus.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

public final class OpenApiSchemaAssertions {

  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  private static final JsonNode OPEN_API = loadOpenApi();
  private static final JsonSchemaFactory SCHEMA_FACTORY =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

  private OpenApiSchemaAssertions() {}

  public static void assertResponse(String schemaName, String responseBody) {
    try {
      assertResponse(schemaName, JSON.readTree(responseBody));
    } catch (IOException exception) {
      throw new AssertionError("Response is not valid JSON", exception);
    }
  }

  public static void assertResponse(String schemaName, JsonNode responseBody) {
    assertThat(OPEN_API.at("/components/schemas/" + schemaName).isMissingNode())
        .as("OpenAPI response schema %s exists", schemaName)
        .isFalse();
    ObjectNode root = normalizedOpenApi(schemaName);
    root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    root.put("$ref", "#/components/schemas/" + schemaName);
    JsonSchema schema = SCHEMA_FACTORY.getSchema(root);
    Set<ValidationMessage> failures = schema.validate(responseBody);
    assertThat(failures)
        .as("Response must match OpenAPI schema %s: %s", schemaName, failures)
        .isEmpty();
  }

  private static ObjectNode normalizedOpenApi(String schemaName) {
    ObjectNode root = OPEN_API.deepCopy();
    JsonNode target = root.at("/components/schemas/" + schemaName);
    flattenAllOf((ObjectNode) target, root);
    return root;
  }

  private static void flattenAllOf(ObjectNode schema, ObjectNode root) {
    JsonNode allOf = schema.get("allOf");
    if (allOf instanceof ArrayNode parts) {
      ObjectNode properties = JSON.createObjectNode();
      Set<String> required = new LinkedHashSet<>();
      for (JsonNode part : parts) {
        JsonNode resolved = resolve(part, root);
        if (resolved instanceof ObjectNode resolvedObject) {
          flattenAllOf(resolvedObject, root);
          resolvedObject
              .path("properties")
              .fields()
              .forEachRemaining(entry -> properties.set(entry.getKey(), entry.getValue()));
          resolvedObject.path("required").forEach(item -> required.add(item.asText()));
        }
      }
      schema
          .path("properties")
          .fields()
          .forEachRemaining(entry -> properties.set(entry.getKey(), entry.getValue()));
      schema.path("required").forEach(item -> required.add(item.asText()));
      schema.remove("allOf");
      schema.remove("unevaluatedProperties");
      schema.put("type", "object");
      schema.put("additionalProperties", false);
      schema.set("properties", properties);
      ArrayNode requiredNode = schema.putArray("required");
      required.forEach(requiredNode::add);
    }
  }

  private static JsonNode resolve(JsonNode schema, ObjectNode root) {
    String reference = schema.path("$ref").asText();
    return reference.startsWith("#/") ? root.at(reference.substring(1)) : schema;
  }

  public static void assertErrorEnvelope(JsonNode responseBody, String expectedCode) {
    assertResponse("ApiError", responseBody);
    assertThat(responseBody.path("code").asText()).isEqualTo(expectedCode);
    assertThat(responseBody.path("message").asText()).isNotBlank();
    assertThat(responseBody.path("traceId").asText()).matches("^[0-9a-f]{32}$");
    assertThat(responseBody.path("timestamp").asText()).endsWith("Z");
  }

  private static JsonNode loadOpenApi() {
    Path root = Path.of("").toAbsolutePath();
    while (root != null) {
      Path contract = root.resolve("packages/api-contract/openapi.yaml");
      if (Files.isRegularFile(contract)) {
        try {
          return new ObjectMapper(new YAMLFactory()).readTree(contract.toFile());
        } catch (IOException exception) {
          throw new ExceptionInInitializerError(exception);
        }
      }
      root = root.getParent();
    }
    throw new ExceptionInInitializerError("Could not locate packages/api-contract/openapi.yaml");
  }
}
