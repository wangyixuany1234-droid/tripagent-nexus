package com.tripagent.nexus.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.domain.model.Phase14Models.DeveloperTrace;
import com.tripagent.nexus.domain.model.Phase14Models.TraceSpanCategory;
import com.tripagent.nexus.infrastructure.config.DeveloperToolsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DeveloperTraceServiceTest {

  @Test
  void sanitizesPromptAndUserAttributesBeforeReturningTrace() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://jaeger.test");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    server
        .expect(requestTo("http://jaeger.test/api/traces/11111111111111111111111111111111"))
        .andRespond(
            withSuccess(
                """
                {"data":[{"spans":[{
                  "spanID":"span-1",
                  "operationName":"gen_ai.chat",
                  "startTime":1788753600000000,
                  "duration":250000,
                  "references":[],
                  "tags":[
                    {"key":"otel.status_code","value":"OK"},
                    {"key":"gen_ai.prompt","value":"private prompt"},
                    {"key":"user.email","value":"private@example.com"},
                    {"key":"result","value":"call 13800138000"}
                  ]
                }]}]}
                """,
                MediaType.APPLICATION_JSON));
    DeveloperToolsProperties properties = new DeveloperToolsProperties();
    properties.setEnabled(true);
    DeveloperTraceService service = new DeveloperTraceService(builder.build(), properties);

    DeveloperTrace trace = service.get("11111111111111111111111111111111");

    assertThat(trace.spans()).hasSize(1);
    assertThat(trace.spans().get(0).category()).isEqualTo(TraceSpanCategory.MODEL);
    assertThat(trace.spans().get(0).attributes())
        .containsEntry("gen_ai.prompt", "***")
        .containsEntry("user.email", "***")
        .containsEntry("result", "call 138****8000");
    server.verify();
  }

  @Test
  void deniesTraceAccessWhenDeveloperToolsAreDisabledRegardlessOfRole() {
    DeveloperToolsProperties properties = new DeveloperToolsProperties();
    DeveloperTraceService service =
        new DeveloperTraceService(RestClient.create("http://jaeger.test"), properties);

    assertThatThrownBy(() -> service.authorize(null))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> {
              assertThat(exception.getCode()).isEqualTo("DEVELOPER_TRACE_FORBIDDEN");
              assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            });
    assertThatThrownBy(() -> service.authorize("ADMIN"))
        .isInstanceOfSatisfying(
            ApiException.class,
            exception -> {
              assertThat(exception.getCode()).isEqualTo("DEVELOPER_TRACE_FORBIDDEN");
              assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            });
  }
}
