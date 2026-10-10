package com.tripagent.nexus.skill;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.guardrail.ExternalDomainGuard;
import com.tripagent.nexus.infrastructure.config.McpProperties;
import com.tripagent.nexus.observability.TraceContextProvider;
import com.tripagent.nexus.observability.TraceOperations;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.agents.enabled", havingValue = "true", matchIfMissing = true)
public class OfficialMcpToolGateway implements McpToolGateway {

  private final ObjectMapper objectMapper;
  private final McpProperties properties;
  private final McpToolAuthorizer authorizer;
  private final TraceContextProvider traceContextProvider;
  private final TraceOperations traces;
  private final ExternalDomainGuard domainGuard;
  private final McpSchemaCatalog schemaCatalog;
  private final McpJsonSchemaValidator schemaValidator;
  private final HttpClient sharedHttpClient;

  public OfficialMcpToolGateway(
      ObjectMapper objectMapper,
      McpProperties properties,
      McpToolAuthorizer authorizer,
      TraceContextProvider traceContextProvider,
      TraceOperations traces,
      ExternalDomainGuard domainGuard,
      McpSchemaCatalog schemaCatalog) {
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.authorizer = authorizer;
    this.traceContextProvider = traceContextProvider;
    this.traces = traces;
    this.domainGuard = domainGuard;
    this.schemaCatalog = schemaCatalog;
    this.schemaValidator = new McpJsonSchemaValidator(objectMapper);
    this.sharedHttpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.getRequestTimeout())
            .build();
  }

  @Override
  public Set<String> discover(AgentName agentName) {
    try (McpSyncClient client = createClient(agentName, null)) {
      initializeAndValidate(client);
      return validateManifest(client.listTools().tools(), agentName);
    }
  }

  @Override
  public McpToolResult call(
      AgentName agentName, McpToolName toolName, Map<String, Object> arguments) {
    authorizer.requireAllowed(agentName.cardName(), toolName);
    java.util.UUID ownerTaskId = McpInvocationContext.ownerTaskId().orElse(null);
    return traces.trace(
        "mcp.client.call",
        "tripagent.mcp.tool",
        Map.of("agent.name", agentName.cardName(), "mcp.tool.name", toolName.value()),
        () -> {
          try (McpSyncClient client = createClient(agentName, ownerTaskId)) {
            initializeAndValidate(client);
            Set<String> tools = validateManifest(client.listTools().tools(), agentName);
            if (!tools.contains(toolName.value())) {
              throw new IllegalStateException("MCP_TOOL_MANIFEST_INVALID");
            }
            McpSchema.CallToolResult response =
                client.callTool(
                    McpSchema.CallToolRequest.builder(toolName.value())
                        .arguments(arguments)
                        .build());
            if (response == null || Boolean.TRUE.equals(response.isError())) {
              throw new IllegalStateException("MCP_RESULT_INVALID");
            }
            var validation =
                schemaValidator.validate(
                    schemaCatalog.outputSchema(toolName), response.structuredContent());
            if (!validation.valid()) {
              throw new IllegalStateException("MCP_RESULT_SCHEMA_INVALID");
            }
            return objectMapper.convertValue(response.structuredContent(), McpToolResult.class);
          }
        },
        (span, result) -> {
          span.tag("mcp.provider.mode", result.providerMode().name());
          span.tag("mcp.tool.status", result.status().name());
        });
  }

  private void initializeAndValidate(McpSyncClient client) {
    McpSchema.InitializeResult initialization = client.initialize();
    McpSchema.Implementation server = initialization.serverInfo();
    if (!properties.getExpectedServerName().equals(server.name())
        || !properties.getExpectedServerVersion().equals(server.version())) {
      throw new IllegalStateException("MCP_SERVER_IDENTITY_INVALID");
    }
  }

  private Set<String> validateManifest(List<McpSchema.Tool> tools, AgentName agentName) {
    Set<String> seen = new HashSet<>();
    Set<String> allowed = new HashSet<>();
    for (McpSchema.Tool advertised : tools) {
      if (!seen.add(advertised.name())) {
        throw new IllegalStateException("MCP_TOOL_NAME_COLLISION");
      }
      McpToolName name;
      try {
        name = toolName(advertised.name());
      } catch (IllegalArgumentException exception) {
        continue;
      }
      if (!schemaCatalog.matches(name, advertised)) {
        throw new IllegalStateException("MCP_TOOL_MANIFEST_INVALID");
      }
      try {
        authorizer.requireAllowed(agentName.cardName(), name);
        allowed.add(advertised.name());
      } catch (McpToolAuthorizer.McpAuthorizationException ignored) {
        // Discovery returns the authenticated Agent's approved capability subset.
      }
    }
    return Set.copyOf(allowed);
  }

  private McpSyncClient createClient(AgentName agentName, java.util.UUID ownerTaskId) {
    domainGuard.requireAllowed(properties.getBaseUrl());
    HttpRequest.Builder requestBuilder =
        HttpRequest.newBuilder()
            .header("Authorization", "Bearer " + properties.getServiceToken())
            .header("X-Agent-Name", agentName.cardName())
            .header("X-Trace-Id", traceContextProvider.currentOrNewTraceId());
    if (ownerTaskId != null) {
      requestBuilder.header("X-Owner-Task-Id", ownerTaskId.toString());
    }
    HttpClientStreamableHttpTransport transport =
        HttpClientStreamableHttpTransport.builder(properties.getBaseUrl().toString())
            .endpoint(properties.getEndpoint())
            .clientBuilder(new SharedHttpClientBuilder(sharedHttpClient))
            .requestBuilder(requestBuilder)
            .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
            .connectTimeout(properties.getRequestTimeout())
            .maxResponseSize(properties.getMaxRequestSize())
            .build();
    return McpClient.sync(transport)
        .requestTimeout(properties.getRequestTimeout())
        .initializationTimeout(properties.getRequestTimeout())
        .jsonSchemaValidator(new McpJsonSchemaValidator(objectMapper))
        .clientInfo(McpSchema.Implementation.builder("tripagent-agent-client", "1.0.0").build())
        .build();
  }

  private static McpToolName toolName(String value) {
    for (McpToolName name : McpToolName.values()) {
      if (name.value().equals(value)) {
        return name;
      }
    }
    throw new IllegalArgumentException("Unknown MCP tool: " + value);
  }

  /**
   * Delegates every configuration call to a real builder but always returns the gateway's single
   * shared {@link HttpClient}. The MCP SDK creates one transport per discover/call and never closes
   * an internally built client, which leaked a selector and worker threads per invocation.
   */
  private static final class SharedHttpClientBuilder implements HttpClient.Builder {

    private final HttpClient.Builder delegate = HttpClient.newBuilder();
    private final HttpClient shared;

    private SharedHttpClientBuilder(HttpClient shared) {
      this.shared = shared;
    }

    @Override
    public HttpClient build() {
      return shared;
    }

    @Override
    public HttpClient.Builder cookieHandler(CookieHandler cookieHandler) {
      delegate.cookieHandler(cookieHandler);
      return this;
    }

    @Override
    public HttpClient.Builder connectTimeout(Duration timeout) {
      delegate.connectTimeout(timeout);
      return this;
    }

    @Override
    public HttpClient.Builder sslContext(SSLContext sslContext) {
      delegate.sslContext(sslContext);
      return this;
    }

    @Override
    public HttpClient.Builder sslParameters(SSLParameters sslParameters) {
      delegate.sslParameters(sslParameters);
      return this;
    }

    @Override
    public HttpClient.Builder executor(Executor executor) {
      delegate.executor(executor);
      return this;
    }

    @Override
    public HttpClient.Builder followRedirects(HttpClient.Redirect policy) {
      delegate.followRedirects(policy);
      return this;
    }

    @Override
    public HttpClient.Builder version(HttpClient.Version version) {
      delegate.version(version);
      return this;
    }

    @Override
    public HttpClient.Builder priority(int priority) {
      delegate.priority(priority);
      return this;
    }

    @Override
    public HttpClient.Builder proxy(java.net.ProxySelector proxy) {
      delegate.proxy(proxy);
      return this;
    }

    @Override
    public HttpClient.Builder authenticator(Authenticator authenticator) {
      delegate.authenticator(authenticator);
      return this;
    }
  }
}
