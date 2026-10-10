package com.tripagent.nexus.application.planning;

import com.tripagent.nexus.agent.AgentContracts.BookingContext;
import com.tripagent.nexus.agent.AgentContracts.ConstraintKind;
import com.tripagent.nexus.agent.AgentContracts.PrivacyClassification;
import com.tripagent.nexus.agent.AgentContracts.PrivacyLabel;
import com.tripagent.nexus.agent.AgentContracts.UserConstraint;
import com.tripagent.nexus.agent.AgentName;
import com.tripagent.nexus.agent.ContextEnvelope;
import com.tripagent.nexus.domain.model.DomainEnums.BookingReviewStatus;
import com.tripagent.nexus.domain.model.ReplanInputSnapshot;
import com.tripagent.nexus.domain.model.TravelRequirement;
import com.tripagent.nexus.infrastructure.config.PlanningProperties;
import com.tripagent.nexus.infrastructure.persistence.entity.ParsedBookingEntity;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PlanningContextFactory {

  private final PlanningProperties properties;

  public PlanningContextFactory(PlanningProperties properties) {
    this.properties = properties;
  }

  public ContextEnvelope create(
      TravelRequirement requirement,
      List<ParsedBookingEntity> bookings,
      PlanningKnowledgeService.KnowledgeResult knowledge,
      int planVersion,
      ReplanInputSnapshot replanInput) {
    List<UserConstraint> constraints = new ArrayList<>();
    constraints.add(
        hard(
            "dates",
            "Travel dates are "
                + requirement.startDate()
                + " through "
                + requirement.endDate()
                + "."));
    constraints.add(
        hard(
            "budget",
            "Total budget is "
                + (replanInput.adjustedTotalBudget() == null
                    ? requirement.totalBudget()
                    : replanInput.adjustedTotalBudget())
                + " "
                + requirement.currency()
                + "."));
    constraints.add(hard("travelers", "Traveler count is " + requirement.travelers().size() + "."));
    constraints.add(
        new UserConstraint(
            "pace",
            ConstraintKind.SOFT,
            "Preferred pace is "
                + (replanInput.adjustedPace() == null
                    ? requirement.pace()
                    : replanInput.adjustedPace())
                + ".",
            false));
    if (!requirement.interests().isEmpty() || !replanInput.addedPreferences().isEmpty()) {
      List<String> interests = new ArrayList<>(requirement.interests());
      interests.addAll(replanInput.addedPreferences());
      constraints.add(
          new UserConstraint(
              "interests",
              ConstraintKind.SOFT,
              "Interests: " + String.join(", ", interests) + ".",
              false));
    }
    requirement.mustVisit().stream()
        .map(value -> hard("must-visit-" + constraints.size(), "Must visit: " + value + "."))
        .forEach(constraints::add);
    requirement.avoid().stream()
        .map(value -> hard("avoid-" + constraints.size(), "Avoid: " + value + "."))
        .forEach(constraints::add);

    List<BookingContext> bookingContexts =
        bookings.stream()
            .map(
                booking ->
                    new BookingContext(
                        booking.getBookingId(),
                        booking.getType().name(),
                        booking.getStartTime(),
                        booking.getEndTime(),
                        booking.getAmount(),
                        booking.getCurrency(),
                        booking.getReviewStatus() != BookingReviewStatus.NEEDS_REVIEW,
                        booking.getType().name()
                            + " booking"
                            + (booking.getDestination() == null
                                ? ""
                                : " to " + booking.getDestination())))
            .toList();

    List<PrivacyLabel> privacyLabels = new ArrayList<>();
    for (int index = 0; index < bookingContexts.size(); index++) {
      privacyLabels.add(
          new PrivacyLabel(
              "/parsedBookings/" + index,
              PrivacyClassification.PERSONAL,
              EnumSet.of(
                  AgentName.REQUIREMENT,
                  AgentName.TRANSPORT,
                  AgentName.ACCOMMODATION,
                  AgentName.BUDGET,
                  AgentName.REVIEW,
                  AgentName.COORDINATOR)));
    }
    int interestIndex = indexOf(constraints, "interests");
    if (interestIndex >= 0) {
      privacyLabels.add(
          new PrivacyLabel(
              "/userConstraints/" + interestIndex,
              PrivacyClassification.PERSONAL,
              EnumSet.of(
                  AgentName.REQUIREMENT,
                  AgentName.RESEARCH,
                  AgentName.RECOMMENDATION,
                  AgentName.REVIEW,
                  AgentName.COORDINATOR)));
    }

    return new ContextEnvelope(
        List.of(
            "Use UTC instants for persisted time.",
            "Do not weaken hard user constraints.",
            "Do not present MOCK evidence as live.",
            "Java RuleEngine is authoritative for deterministic validation."),
        constraints,
        bookingContexts,
        knowledge.evidence(),
        List.of(),
        List.of(),
        planVersion,
        properties.getTokenBudget(),
        privacyLabels);
  }

  private static UserConstraint hard(String id, String statement) {
    return new UserConstraint(id, ConstraintKind.HARD, statement, true);
  }

  private static int indexOf(List<UserConstraint> constraints, String id) {
    for (int index = 0; index < constraints.size(); index++) {
      if (constraints.get(index).constraintId().equals(id)) {
        return index;
      }
    }
    return -1;
  }
}
