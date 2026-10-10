package com.tripagent.nexus.guardrail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tripagent.nexus.infrastructure.config.UploadProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.util.unit.DataSize;

class UploadPolicyTest {

  private UploadPolicy policy;

  @BeforeEach
  void setUp() {
    UploadProperties properties = new UploadProperties();
    properties.setMaxSize(DataSize.ofMegabytes(10));
    properties.setAllowedMimeTypes(
        Set.of("image/jpeg", "image/png", "image/webp", "application/pdf"));
    properties.setAllowedExtensions(Set.of(".jpg", ".jpeg", ".png", ".webp", ".pdf"));
    policy = new UploadPolicy(properties);
  }

  @Test
  void acceptsConfiguredTypeExtensionAndSize() {
    assertThatCode(() -> policy.validate("ticket.JPG", "IMAGE/JPEG", 1024))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsMimeTypeEvenWhenExtensionIsAllowed() {
    assertThatThrownBy(() -> policy.validate("ticket.jpg", "text/html", 1024))
        .isInstanceOf(UploadPolicy.UploadPolicyViolation.class)
        .hasMessage("UPLOAD_MIME_TYPE_DENIED");
  }

  @Test
  void rejectsDisallowedExtension() {
    assertThatThrownBy(() -> policy.validate("ticket.exe", "application/pdf", 1024))
        .isInstanceOf(UploadPolicy.UploadPolicyViolation.class)
        .hasMessage("UPLOAD_EXTENSION_DENIED");
  }

  @Test
  void rejectsOversizedFile() {
    assertThatThrownBy(
            () ->
                policy.validate(
                    "ticket.pdf", "application/pdf", DataSize.ofMegabytes(11).toBytes()))
        .isInstanceOf(UploadPolicy.UploadPolicyViolation.class)
        .hasMessage("UPLOAD_SIZE_INVALID");
  }

  @Test
  void rejectsEmptyFile() {
    assertThatThrownBy(() -> policy.validate("ticket.pdf", "application/pdf", 0))
        .isInstanceOf(UploadPolicy.UploadPolicyViolation.class)
        .hasMessage("UPLOAD_SIZE_INVALID");
  }

  @Test
  void detectsActualMimeTypeAndRejectsSpoofing() {
    byte[] png =
        Base64.getDecoder()
            .decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk"
                    + "+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    assertThatThrownBy(() -> policy.inspect("ticket.jpg", "image/jpeg", png))
        .isInstanceOf(UploadPolicy.UploadPolicyViolation.class)
        .hasMessage("UPLOAD_MIME_MISMATCH");
  }

  @Test
  void rejectsExecutableMagicEvenWithAllowedMetadata() {
    byte[] executable = new byte[] {'M', 'Z', 0, 0, 0, 0};

    assertThatThrownBy(() -> policy.inspect("ticket.pdf", "application/pdf", executable))
        .isInstanceOf(UploadPolicy.UploadPolicyViolation.class)
        .hasMessage("UPLOAD_EXECUTABLE_DENIED");
  }

  @ParameterizedTest
  @MethodSource("supportedFixtures")
  void acceptsSupportedFixtureFormats(String fileName, String mediaType, int pageCount)
      throws IOException {
    byte[] content = Files.readAllBytes(findFixture(fileName));

    UploadPolicy.UploadInspection inspection = policy.inspect(fileName, mediaType, content);

    assertThat(inspection.mediaType()).isEqualTo(mediaType);
    assertThat(inspection.pageCount()).isEqualTo(pageCount);
  }

  private static Stream<Arguments> supportedFixtures() {
    return Stream.of(
        Arguments.of("hotel-confirmation.png", "image/png", 1),
        Arguments.of("flight-confirmation.jpg", "image/jpeg", 1),
        Arguments.of("attraction-ticket.webp", "image/webp", 1),
        Arguments.of("duplicate-orders.pdf", "application/pdf", 2));
  }

  private static Path findFixture(String fileName) {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      Path candidate = directory.resolve("qa/fixtures/documents/" + fileName);
      if (Files.exists(candidate)) {
        return candidate;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException("Could not locate document fixture " + fileName);
  }
}
