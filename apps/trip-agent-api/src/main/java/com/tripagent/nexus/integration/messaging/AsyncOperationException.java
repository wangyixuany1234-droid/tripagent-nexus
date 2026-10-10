package com.tripagent.nexus.integration.messaging;

public class AsyncOperationException extends RuntimeException {

  private final String code;
  private final boolean retryable;
  private final String userMessage;

  public AsyncOperationException(
      String code, boolean retryable, String userMessage, Throwable cause) {
    super(code, cause);
    this.code = code;
    this.retryable = retryable;
    this.userMessage = userMessage;
  }

  public AsyncOperationException(String code, boolean retryable, String userMessage) {
    this(code, retryable, userMessage, null);
  }

  public String code() {
    return code;
  }

  public boolean retryable() {
    return retryable;
  }

  public String userMessage() {
    return userMessage;
  }
}
