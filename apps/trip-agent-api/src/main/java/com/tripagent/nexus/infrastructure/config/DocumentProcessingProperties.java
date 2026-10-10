package com.tripagent.nexus.infrastructure.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.document-processing")
public class DocumentProcessingProperties {

  private boolean messagingEnabled = true;

  @NotBlank private String requestedTopic = "document.parse.requested";

  @NotBlank private String completedTopic = "document.parse.completed";

  @NotBlank private String locale = "zh-CN";

  @DecimalMin("0.0") @DecimalMax("1.0") private BigDecimal criticalConfidenceThreshold = new BigDecimal("0.7500");

  public boolean isMessagingEnabled() {
    return messagingEnabled;
  }

  public void setMessagingEnabled(boolean messagingEnabled) {
    this.messagingEnabled = messagingEnabled;
  }

  public String getRequestedTopic() {
    return requestedTopic;
  }

  public void setRequestedTopic(String requestedTopic) {
    this.requestedTopic = requestedTopic;
  }

  public String getCompletedTopic() {
    return completedTopic;
  }

  public void setCompletedTopic(String completedTopic) {
    this.completedTopic = completedTopic;
  }

  public String getLocale() {
    return locale;
  }

  public void setLocale(String locale) {
    this.locale = locale;
  }

  public BigDecimal getCriticalConfidenceThreshold() {
    return criticalConfidenceThreshold;
  }

  public void setCriticalConfidenceThreshold(BigDecimal criticalConfidenceThreshold) {
    this.criticalConfidenceThreshold = criticalConfidenceThreshold;
  }
}
