package com.tripagent.nexus.integration;

import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseProvider.EMBEDDED;
import static io.zonky.test.db.AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tripagent.nexus.agent.AgentCardFactory;
import com.tripagent.nexus.agent.AgentContracts.AgentPolicy;
import com.tripagent.nexus.agent.AgentContracts.AgentResultEnvelope;
import com.tripagent.nexus.agent.AgentContracts.RetryPolicy;
import com.tripagent.nexus.agent.AgentDefinition;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.AgentStatus;
import com.tripagent.nexus.agent.ContextEnvelope;
import com.tripagent.nexus.agent.coordinator.CoordinatorAgentModels;
import com.tripagent.nexus.agent.research.ResearchAgentModels;
import com.tripagent.nexus.application.planning.AgentCheckpointService;
import com.tripagent.nexus.application.planning.PlanningCheckpointService;
import com.tripagent.nexus.application.planning.PlanningKnowledgeService;
import com.tripagent.nexus.application.service.AgentExecutionService;
import com.tripagent.nexus.application.service.TaskApplicationService;
import com.tripagent.nexus.domain.model.DomainEnums.TaskType;
import com.tripagent.nexus.infrastructure.persistence.repository.AgentResultRepository;
import io.zonky.test.db.AutoConfigureEmbeddedDatabase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureEmbeddedDatabase(provider = EMBEDDED, type = POSTGRES)
class AgentResumeReproductionTest {
  @Autowired AgentExecutionService agents;
  @Autowired TaskApplicationService tasks;
  @Autowired AgentResultRepository results;
  @Autowired ObjectMapper mapper;
  @Autowired PlanningCheckpointService checkpoints;
  @Autowired AgentCheckpointService steps;

  @Test
  void displayProjectionAloneCannotReconstructAgentOutput() {
    var task = tasks.create(TaskType.PLAN_GENERATION, UUID.randomUUID());
    tasks.transition(
        task.taskId(),
        com.tripagent.nexus.domain.model.DomainEnums.TaskStatus.RUNNING,
        "AGENTS_RUNNING",
        40,
        "Synthetic running workflow");
    var context = ContextEnvelope.empty(1, 8000);
    var snapshot =
        checkpoints.create(
            task.taskId(),
            "synthetic-hash",
            "synthetic-tenant",
            new CoordinatorAgentModels.Input(task.taskId(), context, "Synthetic request"),
            new PlanningKnowledgeService.KnowledgeResult(List.of(), true, List.of()));
    var definition =
        new AgentDefinition<>(
            AgentName.RESEARCH,
            AgentCardFactory.create(AgentName.RESEARCH, "Synthetic", java.util.Set.of(), "1.0.0"),
            ResearchAgentModels.Input.class,
            ResearchAgentModels.Output.class,
            java.util.Set.of(),
            new AgentPolicy(
                java.time.Duration.ofSeconds(1),
                new RetryPolicy(1, java.time.Duration.ZERO, java.time.Duration.ZERO),
                java.util.Set.of(),
                true,
                "research-v1"));
    var input =
        new ResearchAgentModels.Input(
            UUID.randomUUID(), context, List.of("Beijing"), List.of("area"));
    var claim =
        steps.begin(
            task.taskId(),
            snapshot.input().executionToken(),
            definition,
            input,
            "synthetic-tenant");
    var id = claim.executionId();
    var output = mapper.createObjectNode().put("destinationSummary", "Synthetic research");
    output.putArray("candidateAreas").add("Synthetic area");
    output.put("evidenceInsufficient", false);
    steps.complete(
        task.taskId(),
        snapshot.input().executionToken(),
        id,
        definition,
        new AgentResultEnvelope<>(
            UUID.randomUUID(),
            AgentName.RESEARCH,
            AgentStatus.SUCCEEDED,
            output,
            List.of(),
            List.of(),
            task.traceId()),
        1);
    var resumed = checkpoints.resume(task.taskId(), "synthetic-hash", "synthetic-tenant");
    var reused =
        steps.begin(
            task.taskId(), resumed.input().executionToken(), definition, input, "synthetic-tenant");
    assertThat(reused.result().result().path("candidateAreas"))
        .as("Resume reconstructs validated output from private checkpoint, not display projection")
        .isEqualTo(output.path("candidateAreas"));
    assertThat(reused.executionId()).isEqualTo(id);
    assertThat(results.findById(id).orElseThrow().getResult().has("candidateAreas")).isFalse();
  }
}
