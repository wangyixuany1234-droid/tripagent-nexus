package com.tripagent.nexus.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ErrorCodeDocumentationTest {

  private static final Pattern CODE_LITERAL = Pattern.compile("\"([A-Z][A-Z0-9_]{2,})\"");
  private static final Pattern DOCUMENTED_CODE = Pattern.compile("`([A-Z][A-Z0-9_]{2,})`");
  private static final Pattern ERROR_SUFFIX =
      Pattern.compile(
          ".*(?:_NOT_FOUND|_INVALID|_FAILED|_MISMATCH|_REQUIRED|_CONFLICT|_UNAVAILABLE|"
              + "_UNAUTHORIZED|_EXCEEDED|_INCOMPLETE|_REUSED|_DISABLED|_EXHAUSTED|"
              + "_GONE|_LIMITED|_DETECTED|_PENDING|_REPLANNABLE|_REJECTED|_UNSAFE|"
              + "_TOO_LARGE|_UNSUPPORTED|_NOT_ALLOWED|_NOT_CONFIGURED)$");

  @Test
  void everySourceErrorCodeIsDocumented() throws IOException {
    Path root = findRepositoryRoot();
    Set<String> sourceCodes = new TreeSet<>();
    try (Stream<Path> files =
        Files.walk(root.resolve("apps/trip-agent-api/src/main/java"))
            .filter(path -> path.toString().endsWith(".java"))) {
      files.forEach(path -> sourceCodes.addAll(extract(read(path), true)));
    }
    Set<String> documentedCodes =
        extract(read(root.resolve("docs/错误码.md")), DOCUMENTED_CODE, false);

    assertThat(documentedCodes)
        .as("docs/错误码.md must include every stable source error code")
        .containsAll(sourceCodes);
  }

  private static Set<String> extract(String content, boolean errorsOnly) {
    return extract(content, CODE_LITERAL, errorsOnly);
  }

  private static Set<String> extract(String content, Pattern pattern, boolean errorsOnly) {
    Set<String> codes = new TreeSet<>();
    Matcher matcher = pattern.matcher(content);
    while (matcher.find()) {
      String code = matcher.group(1);
      if (!errorsOnly || ERROR_SUFFIX.matcher(code).matches()) {
        codes.add(code);
      }
    }
    return codes;
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not read " + path, exception);
    }
  }

  private static Path findRepositoryRoot() {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null) {
      if (Files.exists(directory.resolve("compose.yaml"))) {
        return directory;
      }
      directory = directory.getParent();
    }
    throw new IllegalStateException("Could not locate repository root");
  }
}
