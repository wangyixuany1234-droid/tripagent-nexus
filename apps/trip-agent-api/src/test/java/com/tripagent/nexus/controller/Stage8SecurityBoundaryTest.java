package com.tripagent.nexus.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.application.idempotency.IdempotencyService;
import com.tripagent.nexus.application.security.AuthenticatedActorService;
import com.tripagent.nexus.application.security.ResourceAuthorizationService;
import com.tripagent.nexus.application.service.DeveloperTraceService;
import com.tripagent.nexus.application.service.TripApplicationService;
import com.tripagent.nexus.common.error.GlobalExceptionHandler;
import com.tripagent.nexus.guardrail.PromptInjectionGuard;
import com.tripagent.nexus.infrastructure.config.DeveloperToolsProperties;
import com.tripagent.nexus.infrastructure.config.GuardrailProperties;
import com.tripagent.nexus.infrastructure.config.SecurityProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.TripEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.DocumentRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TaskRecordRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelPlanRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TravelRequirementRepository;
import com.tripagent.nexus.infrastructure.persistence.repository.TripRepository;
import com.tripagent.nexus.observability.TraceContextProvider;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

/** MVC + real authorization service, synthetic repository; not a full HTTP/DB test. */
class Stage8SecurityBoundaryTest {
  private static final UUID TRIP = UUID.fromString("40000000-0000-0000-0000-000000000008");
  private static final UUID OWNER = UUID.fromString("50000000-0000-0000-0000-000000000008");
  private MockMvc mvc;

  @BeforeEach
  void prepare() {
    var repository = mock(TripRepository.class);
    when(repository.findById(TRIP))
        .thenReturn(
            Optional.of(
                new TripEntity(TRIP, OWNER, "SYNTHETIC_PRIVATE_TRIP", "UTC", Instant.EPOCH)));
    var service =
        new TripApplicationService(
            repository,
            mock(TravelRequirementRepository.class),
            new ObjectMapper().findAndRegisterModules(),
            Clock.systemUTC(),
            new PromptInjectionGuard(new GuardrailProperties()));
    var authorization =
        new ResourceAuthorizationService(
            new AuthenticatedActorService(new SecurityProperties()),
            repository,
            mock(TravelPlanRepository.class),
            mock(DocumentRepository.class),
            mock(TaskRecordRepository.class));
    var traceContext = mock(TraceContextProvider.class);
    when(traceContext.currentOrNewTraceId()).thenReturn("0".repeat(32));
    mvc =
        MockMvcBuilders.standaloneSetup(
                new TripController(service, mock(IdempotencyService.class), authorization))
            .setControllerAdvice(new GlobalExceptionHandler(traceContext, Clock.systemUTC()))
            .build();
  }

  @Test
  void anonymousMustNotReadPrivateTrip() throws Exception {
    var response = mvc.perform(get("/api/v1/trips/" + TRIP)).andReturn().getResponse();
    assertThat(response.getStatus())
        .as("Anonymous response must not return owner's trip")
        .isIn(401, 403, 404);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Bearer synthetic-invalid", "Bearer e30.e30.invalid"})
  void invalidCredentialMustNotReadPrivateTrip(String credential) throws Exception {
    var response =
        mvc.perform(get("/api/v1/trips/" + TRIP).header("Authorization", credential))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isIn(401, 403, 404);
  }

  @Test
  void selfAssertedOwnerHeaderMustNotAuthenticate() throws Exception {
    var response =
        mvc.perform(get("/api/v1/trips/" + TRIP).header("X-User-Id", OWNER))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isIn(401, 403, 404);
  }

  @Test
  void spoofedAdminHeaderMustNotAuthorize() {
    var settings = new DeveloperToolsProperties();
    settings.setEnabled(false);
    var service = new DeveloperTraceService(RestClient.create("http://127.0.0.1:65535"), settings);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.authorize("ADMIN"))
        .isInstanceOf(com.tripagent.nexus.common.error.ApiException.class);
  }
}
