package com.tripagent.nexus.common.error;

import com.tripagent.nexus.controller.dto.ApiModels.ErrorDetail;
import java.util.List;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

  private final String code;
  private final HttpStatus status;
  private final List<ErrorDetail> details;

  public ApiException(String code, HttpStatus status, String message) {
    this(code, status, message, List.of());
  }

  public ApiException(String code, HttpStatus status, String message, List<ErrorDetail> details) {
    super(message);
    this.code = code;
    this.status = status;
    this.details = List.copyOf(details);
  }

  public String getCode() {
    return code;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public List<ErrorDetail> getDetails() {
    return details;
  }
}
