package com.tripagent.nexus.agent;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.io.IOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

@Component
public class AgentSchemaValidator {

  private static final Set<String> AGENT_ENUM_CONSTANTS =
      Arrays.stream(AgentName.values())
          .map(AgentName::name)
          .collect(Collectors.toUnmodifiableSet());

  private final ObjectMapper objectMapper;
  private final Validator validator;
  private final JsonSchemaFactory schemaFactory;
  private final ConcurrentMap<Class<?>, JsonSchema> schemas = new ConcurrentHashMap<>();

  public AgentSchemaValidator(ObjectMapper objectMapper, Validator validator) {
    this.objectMapper = objectMapper;
    this.validator = validator;
    this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  }

  public <T> T parseAndValidate(Object raw, Class<T> type, String errorCode) {
    try {
      JsonNode node = objectMapper.valueToTree(raw);
      T value =
          objectMapper
              .readerFor(type)
              .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
              .readValue(node);
      validate(value, type, errorCode);
      return value;
    } catch (IOException | IllegalArgumentException exception) {
      throw new AgentValidationException(errorCode, "Agent payload does not match its schema.");
    }
  }

  public <T> void validate(T value, Class<?> schemaType, String errorCode) {
    validateConstraints(value, errorCode);

    JsonSchema schema = schemas.computeIfAbsent(schemaType, this::createSchema);
    Set<ValidationMessage> errors = schema.validate(objectMapper.valueToTree(value));
    if (!errors.isEmpty()) {
      String details =
          errors.stream()
              .map(ValidationMessage::getMessage)
              .sorted()
              .collect(Collectors.joining("; "));
      throw new AgentValidationException(errorCode, details);
    }
  }

  private JsonSchema createSchema(Class<?> schemaType) {
    String generatedSchema = new BeanOutputConverter<>(schemaType, objectMapper).getJsonSchema();
    return schemaFactory.getSchema(normalizeGeneratedSchema(generatedSchema, schemaType));
  }

  private String normalizeGeneratedSchema(String generatedSchema, Class<?> schemaType) {
    try {
      JsonNode schema = objectMapper.readTree(generatedSchema);
      normalizeGeneratedSchema(schema);
      normalizeOptionalProperties(schema, schemaType);
      return objectMapper.writeValueAsString(schema);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Agent JSON Schema could not be normalized.", exception);
    }
  }

  private static void normalizeGeneratedSchema(JsonNode node) {
    if (node instanceof ObjectNode objectNode) {
      JsonNode properties = objectNode.get("properties");
      if ("object".equals(objectNode.path("type").asText())
          && (properties == null || (properties.isObject() && properties.isEmpty()))
          && objectNode.path("additionalProperties").isBoolean()
          && !objectNode.path("additionalProperties").asBoolean()) {
        objectNode.put("additionalProperties", true);
      }
      normalizeAgentNameEnum(objectNode.get("enum"));
      objectNode.elements().forEachRemaining(AgentSchemaValidator::normalizeGeneratedSchema);
      return;
    }
    if (node.isArray()) {
      node.elements().forEachRemaining(AgentSchemaValidator::normalizeGeneratedSchema);
    }
  }

  private static void normalizeAgentNameEnum(JsonNode enumNode) {
    if (!(enumNode instanceof ArrayNode values)) {
      return;
    }
    Set<String> generatedValues = new HashSet<>();
    values.forEach(value -> generatedValues.add(value.asText()));
    if (!generatedValues.equals(AGENT_ENUM_CONSTANTS)) {
      return;
    }
    values.removeAll();
    Arrays.stream(AgentName.values()).map(AgentName::cardName).forEach(values::add);
  }

  private static void normalizeOptionalProperties(JsonNode schema, Type javaType) {
    Class<?> rawType = rawType(javaType);
    if (rawType == null) {
      return;
    }
    if (Collection.class.isAssignableFrom(rawType) && javaType instanceof ParameterizedType type) {
      normalizeOptionalProperties(schema.path("items"), type.getActualTypeArguments()[0]);
      return;
    }
    if (!rawType.isRecord() || !(schema.path("properties") instanceof ObjectNode properties)) {
      return;
    }

    ArrayNode required = schema.path("required") instanceof ArrayNode arrayNode ? arrayNode : null;
    for (RecordComponent component : rawType.getRecordComponents()) {
      JsonProperty jsonProperty = component.getAccessor().getAnnotation(JsonProperty.class);
      String propertyName =
          jsonProperty != null && !jsonProperty.value().isBlank()
              ? jsonProperty.value()
              : component.getName();
      if (required != null && jsonProperty != null && !jsonProperty.required()) {
        for (int index = required.size() - 1; index >= 0; index--) {
          if (propertyName.equals(required.get(index).asText())) {
            required.remove(index);
          }
        }
      }
      JsonNode propertySchema = properties.get(propertyName);
      if (propertySchema != null) {
        normalizeOptionalProperties(propertySchema, component.getGenericType());
      }
    }
  }

  private static Class<?> rawType(Type type) {
    if (type instanceof Class<?> rawType) {
      return rawType;
    }
    if (type instanceof ParameterizedType parameterizedType
        && parameterizedType.getRawType() instanceof Class<?> rawType) {
      return rawType;
    }
    return null;
  }

  public <T> void validateConstraints(T value, String errorCode) {
    Set<? extends ConstraintViolation<?>> violations = validator.validate(value);
    if (!violations.isEmpty()) {
      String details =
          violations.stream()
              .map(item -> item.getPropertyPath() + " " + item.getMessage())
              .sorted()
              .collect(Collectors.joining("; "));
      throw new AgentValidationException(errorCode, details);
    }
  }

  public static class AgentValidationException extends RuntimeException {
    private final String code;

    public AgentValidationException(String code, String message) {
      super(message);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
