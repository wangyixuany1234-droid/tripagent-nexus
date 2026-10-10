package com.tripagent.nexus.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SensitiveDataSanitizerTest {

  @Test
  void redactsAuthorizationCookieAndApiKeys() {
    String value =
        "Authorization: Bearer token.value Cookie=session-secret "
            + "api_key=unit-test-value X-API-Key: another-secret";

    String sanitized = SensitiveDataSanitizer.sanitize(value);

    assertThat(sanitized)
        .doesNotContain("token.value")
        .doesNotContain("session-secret")
        .doesNotContain("unit-test-value")
        .doesNotContain("another-secret")
        .contains("Authorization: Bearer ***")
        .contains("Cookie=***")
        .contains("api_key=***")
        .contains("X-API-Key: ***");
  }

  @Test
  void leavesOrdinaryLogFieldsUnchanged() {
    String value = "taskId=018f0e31 status=RUNNING";

    assertThat(SensitiveDataSanitizer.sanitize(value)).isEqualTo(value);
  }

  @Test
  void masksUnlabelledPhoneAndIdentityNumbers() {
    String value = "contact 13812345678 identity 110101199001011234";

    String sanitized = SensitiveDataSanitizer.sanitize(value);

    assertThat(sanitized)
        .doesNotContain("13812345678")
        .doesNotContain("110101199001011234")
        .contains("138****5678")
        .contains("110101********1234");
  }

  @Test
  void redactsPhonePassportOrderAndConfirmationFields() {
    String value =
        "phone=13812345678 passportNo=E12345678 orderId=ORD-20260904-001 "
            + "booking_id=BOOK-001 confirmationNumber=CONF-001";

    String sanitized = SensitiveDataSanitizer.sanitize(value);

    assertThat(sanitized)
        .doesNotContain("13812345678")
        .doesNotContain("E12345678")
        .doesNotContain("ORD-20260904-001")
        .doesNotContain("BOOK-001")
        .doesNotContain("CONF-001")
        .contains("phone=***")
        .contains("passportNo=***")
        .contains("orderId=***")
        .contains("booking_id=***")
        .contains("confirmationNumber=***");
  }

  @Test
  void redactsRawModelTokens() {
    String token = "sk-" + "unit-test-credential-fragment-123456789";
    String value = "provider credential " + token;

    assertThat(SensitiveDataSanitizer.sanitize(value))
        .doesNotContain(token)
        .contains("provider credential ***");
  }

  @Test
  void removesOcrAndEvidenceTextFromStructuredMessages() {
    String input =
        """
        {"ocrText":"Passenger Example CONF-001","textEvidence":"Hotel address"}
        """;

    assertThat(SensitiveDataSanitizer.sanitize(input))
        .doesNotContain("Passenger Example")
        .doesNotContain("Hotel address")
        .contains("\"ocrText\":\"***\"")
        .contains("\"textEvidence\":\"***\"");
  }
}
