package com.tripagent.nexus.controller;

import com.tripagent.nexus.application.security.ResourceAuthorizationService;
import com.tripagent.nexus.application.service.WorkbenchService;
import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.controller.dto.ApiModels.WorkbenchTaskList;
import com.tripagent.nexus.domain.model.DomainEnums.TaskStatus;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workbench/tasks")
@Validated
public class WorkbenchController {

  private final WorkbenchService service;
  private final ResourceAuthorizationService authorization;

  public WorkbenchController(WorkbenchService service, ResourceAuthorizationService authorization) {
    this.service = service;
    this.authorization = authorization;
  }

  @GetMapping
  public WorkbenchTaskList list(
      @RequestParam UUID userId,
      Authentication authentication,
      @RequestParam(required = false) @Size(max = 300) String destination,
      @RequestParam(required = false) String startDate,
      @RequestParam(required = false) String endDate,
      @RequestParam(required = false) TaskStatus status) {
    authorization.requireSameUser(authentication, userId);
    LocalDate parsedStartDate = parseOptionalDate(startDate, "startDate");
    LocalDate parsedEndDate = parseOptionalDate(endDate, "endDate");
    if (parsedStartDate != null
        && parsedEndDate != null
        && parsedEndDate.isBefore(parsedStartDate)) {
      throw new ApiException(
          "WORKBENCH_DATE_RANGE_INVALID",
          HttpStatus.UNPROCESSABLE_ENTITY,
          "The end date must not be before the start date.");
    }
    return new WorkbenchTaskList(
        service.list(userId, destination, parsedStartDate, parsedEndDate, status));
  }

  private static LocalDate parseOptionalDate(String value, String field) {
    if (value == null) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (DateTimeParseException exception) {
      throw new ApiException(
          "REQUEST_INVALID", HttpStatus.BAD_REQUEST, field + " must be a valid ISO date.");
    }
  }
}
