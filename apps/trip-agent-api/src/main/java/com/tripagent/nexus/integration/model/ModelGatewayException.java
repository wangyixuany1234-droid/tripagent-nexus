package com.tripagent.nexus.integration.model;

public class ModelGatewayException extends RuntimeException {

  private final String code;
  private final boolean retryable;

  public ModelGatewayException(String code, boolean retryable, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.retryable = retryable;
  }

  public ModelGatewayException(String code, boolean retryable, String message) {
    this(code, retryable, message, null);
  }

  public String getCode() {
    return code;
  }

  public boolean isRetryable() {
    return retryable;
  }
}
