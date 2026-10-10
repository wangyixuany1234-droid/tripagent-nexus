package com.tripagent.nexus.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import java.util.Map;
import java.util.stream.Collectors;

public class McpJsonSchemaValidator implements JsonSchemaValidator {

  private final ObjectMapper objectMapper;
  private final JsonSchemaFactory schemaFactory;

  public McpJsonSchemaValidator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
    this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  }

  @Override
  public ValidationResponse validate(Map<String, Object> schema, Object instance) {
    try {
      var errors =
          schemaFactory
              .getSchema(objectMapper.valueToTree(schema))
              .validate(objectMapper.valueToTree(instance));
      if (!errors.isEmpty()) {
        return ValidationResponse.asInvalid(
            errors.stream()
                .map(error -> error.getMessage())
                .sorted()
                .collect(Collectors.joining("; ")));
      }
      return ValidationResponse.asValid(objectMapper.writeValueAsString(instance));
    } catch (JsonProcessingException | RuntimeException exception) {
      return ValidationResponse.asInvalid("MCP payload could not be validated.");
    }
  }
}
