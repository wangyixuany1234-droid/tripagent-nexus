package com.tripagent.nexus.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.infrastructure.config.McpProperties;
import com.tripagent.nexus.skill.ItineraryArtifactService.McpToolException;
import com.tripagent.nexus.skill.McpToolAuthorizer.McpAuthorizationException;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.agents.enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(McpProperties.class)
public class McpServerConfiguration {

  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
  private static final String AGENT_CONTEXT_KEY = "agentName";
  private static final String OWNER_TASK_CONTEXT_KEY = "ownerTaskId";

  @Bean
  JacksonMcpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
    return new JacksonMcpJsonMapper(objectMapper);
  }

  @Bean
  McpJsonSchemaValidator mcpJsonSchemaValidator(ObjectMapper objectMapper) {
    return new McpJsonSchemaValidator(objectMapper);
  }

  @Bean
  HttpServletStatelessServerTransport mcpServerTransport(
      JacksonMcpJsonMapper jsonMapper, McpProperties properties) {
    return HttpServletStatelessServerTransport.builder()
        .jsonMapper(jsonMapper)
        .messageEndpoint(properties.getEndpoint())
        .maxRequestSize(properties.getMaxRequestSize())
        .securityValidator(headers -> validateAuthorization(headers, properties.getServiceToken()))
        .contextExtractor(
            request ->
                McpTransportContext.create(
                    Map.of(
                        AGENT_CONTEXT_KEY,
                        nullToEmpty(request.getHeader("X-Agent-Name")),
                        OWNER_TASK_CONTEXT_KEY,
                        nullToEmpty(request.getHeader("X-Owner-Task-Id")),
                        "traceId",
                        nullToEmpty(request.getHeader("X-Trace-Id")))))
        .build();
  }

  @Bean
  ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServletRegistration(
      HttpServletStatelessServerTransport transport, McpProperties properties) {
    ServletRegistrationBean<HttpServletStatelessServerTransport> registration =
        new ServletRegistrationBean<>(transport, properties.getEndpoint());
    registration.setName("tripAgentMcpServlet");
    registration.setLoadOnStartup(1);
    return registration;
  }

  @Bean(destroyMethod = "closeGracefully")
  McpStatelessSyncServer tripAgentMcpServer(
      HttpServletStatelessServerTransport transport,
      JacksonMcpJsonMapper jsonMapper,
      McpJsonSchemaValidator schemaValidator,
      McpSchemaCatalog schemaCatalog,
      McpToolCatalog toolCatalog,
      McpToolAuthorizer authorizer,
      ObjectMapper objectMapper,
      Tracer tracer,
      McpProperties properties) {
    var specification =
        McpServer.sync(transport)
            .serverInfo("tripagent-tools-mcp", "1.0.0")
            .instructions(
                "Travel planning tools. Mock results are labeled and must not be presented as live.")
            .jsonMapper(jsonMapper)
            .jsonSchemaValidator(schemaValidator)
            .validateToolInputs(true)
            .strictToolNameValidation(true)
            .requestTimeout(properties.getRequestTimeout());

    for (McpToolName name : McpToolName.values()) {
      specification.toolCall(
          schemaCatalog.tool(name),
          (transportContext, request) ->
              callTool(
                  name, transportContext, request, toolCatalog, authorizer, objectMapper, tracer));
    }
    return specification.build();
  }

  private static McpSchema.CallToolResult callTool(
      McpToolName name,
      McpTransportContext transportContext,
      McpSchema.CallToolRequest request,
      McpToolCatalog toolCatalog,
      McpToolAuthorizer authorizer,
      ObjectMapper objectMapper,
      Tracer tracer) {
    Span span = tracer.nextSpan().name("mcp.tool.call").tag("mcp.tool.name", name.value()).start();
    try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
      String agentName = String.valueOf(transportContext.get(AGENT_CONTEXT_KEY));
      authorizer.requireAllowed(agentName, name);
      McpCallContext callContext =
          new McpCallContext(
              agentName,
              optionalUuid(String.valueOf(transportContext.get(OWNER_TASK_CONTEXT_KEY))));
      McpToolResult result = toolCatalog.execute(name, request.arguments(), callContext);
      span.tag("mcp.provider.mode", result.providerMode().name());
      span.tag("mcp.tool.status", result.status().name());
      span.tag(
          "operation.success", Boolean.toString(result.status() == McpToolResult.Status.SUCCEEDED));
      Map<String, Object> structured = objectMapper.convertValue(result, MAP_TYPE);
      return McpSchema.CallToolResult.builder()
          .structuredContent(structured)
          .textContent(List.of(objectMapper.writeValueAsString(result)))
          .isError(result.status() == McpToolResult.Status.FAILED)
          .build();
    } catch (McpAuthorizationException exception) {
      span.tag("operation.success", "false");
      span.tag("error.type", exception.code());
      span.error(exception);
      return error(exception.code(), objectMapper);
    } catch (McpToolException | IllegalArgumentException exception) {
      span.tag("operation.success", "false");
      span.error(exception);
      String code =
          exception instanceof McpToolException toolException
              ? toolException.code()
              : "MCP_ARGUMENT_INVALID";
      span.tag("error.type", code);
      return error(code, objectMapper);
    } catch (JsonProcessingException exception) {
      span.tag("operation.success", "false");
      span.tag("error.type", "MCP_RESULT_SERIALIZATION_FAILED");
      span.error(exception);
      return error("MCP_RESULT_SERIALIZATION_FAILED", objectMapper);
    } catch (RuntimeException exception) {
      span.tag("operation.success", "false");
      span.tag("error.type", exception.getClass().getSimpleName());
      span.error(exception);
      return error("MCP_TOOL_EXECUTION_FAILED", objectMapper);
    } finally {
      span.end();
    }
  }

  private static McpSchema.CallToolResult error(String code, ObjectMapper objectMapper) {
    Map<String, Object> structured =
        Map.of(
            "status",
            "FAILED",
            "providerMode",
            "INTERNAL",
            "data",
            Map.of("code", code),
            "warnings",
            List.of());
    String text;
    try {
      text = objectMapper.writeValueAsString(structured);
    } catch (JsonProcessingException exception) {
      text = "{\"status\":\"FAILED\"}";
    }
    return McpSchema.CallToolResult.builder()
        .structuredContent(structured)
        .textContent(List.of(text))
        .isError(true)
        .build();
  }

  private static void validateAuthorization(Map<String, List<String>> headers, String expectedToken)
      throws ServerTransportSecurityException {
    List<String> authorizationValues = new ArrayList<>();
    headers.forEach(
        (key, values) -> {
          if ("authorization".equals(key.toLowerCase(Locale.ROOT))) {
            authorizationValues.addAll(values);
          }
        });
    String expected = "Bearer " + expectedToken;
    boolean valid =
        authorizationValues.stream()
            .anyMatch(
                value ->
                    MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        value.getBytes(StandardCharsets.UTF_8)));
    if (!valid) {
      throw new ServerTransportSecurityException(401, "Unauthorized MCP request.");
    }
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static java.util.UUID optionalUuid(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return java.util.UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new McpAuthorizationException("MCP_CALL_CONTEXT_INVALID");
    }
  }
}
