package com.tripagent.nexus.infrastructure.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Request compatibility layer for the Volcano Ark OpenAI-compatible endpoint.
 *
 * <p>doubao-seed-evolving is a reasoning model: by default it emits large {@code reasoning_content}
 * blobs, which makes structured multi-agent calls slow (often exceeding the coordinator deadline)
 * and occasionally wraps JSON in prose. This customizer rewrites outgoing {@code /chat/completions}
 * bodies to:
 *
 * <ol>
 *   <li>disable extended thinking via the Ark-specific {@code thinking} field, and
 *   <li>enable {@code response_format=json_object} for structured-output calls (identified by the
 *       fixed markers injected by StructuredOutputService).
 * </ol>
 *
 * Plain chat / health-check calls are left without {@code response_format}.
 */
@Configuration
public class ArkChatCompatibilityConfiguration {

  @Bean
  RestClientCustomizer arkChatRequestCustomizer(
      ObjectMapper objectMapper,
      @Value("${app.ark.thinking-disabled:true}") boolean thinkingDisabled) {
    return builder ->
        builder.requestInterceptor(new ArkChatBodyInterceptor(objectMapper, thinkingDisabled));
  }

  static class ArkChatBodyInterceptor implements ClientHttpRequestInterceptor {

    private static final String STRUCTURED_MARKER = "JSON Schema and format requirements";
    private static final String REPAIR_MARKER = "Repair the invalid JSON below";

    private final ObjectMapper objectMapper;
    private final boolean thinkingDisabled;

    ArkChatBodyInterceptor(ObjectMapper objectMapper, boolean thinkingDisabled) {
      this.objectMapper = objectMapper;
      this.thinkingDisabled = thinkingDisabled;
    }

    @Override
    public ClientHttpResponse intercept(
        HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
      String path = request.getURI().getPath();
      if (body == null || body.length == 0 || path == null || !path.contains("/chat/completions")) {
        return execution.execute(request, body);
      }

      try {
        JsonNode root = objectMapper.readTree(body);
        if (!(root instanceof ObjectNode objectNode)) {
          return execution.execute(request, body);
        }

        boolean changed = false;
        if (thinkingDisabled && !objectNode.has("thinking")) {
          objectNode.putObject("thinking").put("type", "disabled");
          changed = true;
        }

        String rawText = new String(body, StandardCharsets.UTF_8);
        boolean structuredCall =
            rawText.contains(STRUCTURED_MARKER) || rawText.contains(REPAIR_MARKER);
        if (structuredCall && !objectNode.has("response_format")) {
          objectNode.putObject("response_format").put("type", "json_object");
          changed = true;
        }

        if (changed) {
          byte[] newBody = objectMapper.writeValueAsBytes(objectNode);
          request.getHeaders().setContentLength(newBody.length);
          request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
          return execution.execute(request, newBody);
        }
      } catch (RuntimeException | IOException ignored) {
        // Never block the request because of a body-rewrite problem.
      }
      return execution.execute(request, body);
    }
  }
}
