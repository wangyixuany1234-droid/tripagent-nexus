package com.tripagent.nexus.guardrail;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.controller.dto.ApiModels.ConfirmGuardedActionRequest;
import com.tripagent.nexus.domain.model.DomainEnums.*;
import com.tripagent.nexus.infrastructure.config.GuardrailProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.GuardedActionEntity;
import com.tripagent.nexus.infrastructure.persistence.repository.*;
import com.tripagent.nexus.observability.TraceContextProvider;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class Stage9ActionSecurityTest {
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"PAYMENT", "CANCELLATION", "REAL_COST", "ORDER_CREATE"})
  void costActionRequiresConfirmationAndSameActionCannotExecuteTwice(String type) {
    var now = Instant.parse("2026-09-15T00:00:00Z");
    var entity =
        new GuardedActionEntity(
            UUID.randomUUID(),
            GuardedActionType.valueOf(type),
            UUID.randomUUID(),
            "Synthetic cost",
            BigDecimal.TEN,
            "CNY",
            now.plusSeconds(3600),
            "synthetic-owner",
            now);
    var repository = mock(GuardedActionRepository.class);
    var audit = mock(GuardedActionAuditRepository.class);
    var service =
        new GuardedActionService(
            repository,
            audit,
            mock(GuardedActionExpirationService.class),
            new PromptInjectionGuard(new GuardrailProperties()),
            mock(TraceContextProvider.class),
            Clock.fixed(now, ZoneOffset.UTC));
    when(repository.findById(entity.getActionId())).thenReturn(Optional.of(entity));
    when(repository.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    when(audit.findByActionIdOrderByOccurredAtAsc(any())).thenReturn(List.of());
    Runnable execute =
        () ->
            service.markExecuted(
                entity.getActionId(),
                entity.getType(),
                entity.getResourceId(),
                entity.getAmount(),
                entity.getCurrency());
    assertThatThrownBy(execute::run).isInstanceOf(ApiException.class);
    verify(repository, never()).saveAndFlush(any());
    var confirmation =
        new ConfirmGuardedActionRequest(entity.getActionId(), 0, true, BigDecimal.TEN, "CNY");
    assertThatThrownBy(() -> service.confirm(entity.getActionId(), confirmation, "synthetic-other"))
        .isInstanceOf(ApiException.class);
    service.confirm(entity.getActionId(), confirmation, "synthetic-owner");
    assertThat(entity.getStatus()).isEqualTo(GuardedActionStatus.CONFIRMED);
    clearInvocations(repository);
    execute.run();
    assertThat(entity.getStatus()).isEqualTo(GuardedActionStatus.EXECUTED);
    assertThatThrownBy(execute::run).isInstanceOf(ApiException.class);
    verify(repository, times(1)).saveAndFlush(any());
  }
}
