package com.tripagent.nexus.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import org.junit.jupiter.api.Test;

class TaskStatusTest {

  @Test
  void supportsTheDeclaredLifecycle() {
    assertThat(TaskStatus.CREATED.canTransitionTo(TaskStatus.QUEUED)).isTrue();
    assertThat(TaskStatus.QUEUED.canTransitionTo(TaskStatus.RUNNING)).isTrue();
    assertThat(TaskStatus.RUNNING.canTransitionTo(TaskStatus.WAITING_CONFIRMATION)).isTrue();
    assertThat(TaskStatus.WAITING_CONFIRMATION.canTransitionTo(TaskStatus.RUNNING)).isTrue();
    assertThat(TaskStatus.RUNNING.canTransitionTo(TaskStatus.SUCCEEDED)).isTrue();
  }

  @Test
  void terminalStatesCannotTransition() {
    assertThat(TaskStatus.SUCCEEDED.isTerminal()).isTrue();
    assertThat(TaskStatus.PARTIAL_SUCCESS.isTerminal()).isTrue();
    assertThat(TaskStatus.FAILED.isTerminal()).isTrue();
    assertThat(TaskStatus.CANCELLED.isTerminal()).isTrue();
    assertThat(TaskStatus.SUCCEEDED.canTransitionTo(TaskStatus.RUNNING)).isFalse();
  }
}
