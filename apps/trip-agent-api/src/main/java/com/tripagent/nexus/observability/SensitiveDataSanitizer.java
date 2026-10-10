package com.tripagent.nexus.observability;

import java.util.regex.Pattern;

public final class SensitiveDataSanitizer {

  private static final String REDACTED = "***";

  private static final Pattern BEARER =
      Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[A-Za-z0-9._~+/=-]+");

  private static final Pattern SENSITIVE_FIELD =
      Pattern.compile(
          "(?i)((?:\"?)(?:cookie|set-cookie|x-api-key|api[_-]?key|deepseek_api_key|phone|mobile|id[_-]?card|identity[_-]?number|passport[_-]?number|passport[_-]?no|order[_-]?id|order[_-]?no|booking[_-]?id|confirmation[_-]?number)(?:\"?)\\s*[:=]\\s*\"?)[^\"\\s,;}]+");

  private static final Pattern SENSITIVE_TEXT_FIELD =
      Pattern.compile(
          "(?i)(\"(?:ocr[_-]?text|raw[_-]?evidence|text[_-]?evidence|image[_-]?data|"
              + "freeTextConstraints|naturalLanguageRequest|userPrompt|systemPrompt|"
              + "query|content|excerpt|interests|mustVisit|avoid)\"\\s*:\\s*\")[^\"]*(\")");

  private static final Pattern RAW_API_TOKEN =
      Pattern.compile("(?i)(?:sk|deepseek)-[A-Za-z0-9][A-Za-z0-9_-]{20,}");

  private static final Pattern CHINESE_MOBILE =
      Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");

  private static final Pattern CHINESE_ID =
      Pattern.compile("(?<!\\d)(\\d{6})\\d{8}(\\d{3}[0-9Xx])(?![0-9Xx])");

  private static final Pattern EMAIL =
      Pattern.compile("(?i)(?<![\\w.+-])[\\w.+-]+@[a-z0-9.-]+\\.[a-z]{2,}(?![\\w.-])");

  private SensitiveDataSanitizer() {}

  public static String sanitize(String value) {
    if (value == null || value.isEmpty()) {
      return value;
    }
    String withoutBearer = BEARER.matcher(value).replaceAll("$1" + REDACTED);
    String withoutText =
        SENSITIVE_TEXT_FIELD.matcher(withoutBearer).replaceAll("$1" + REDACTED + "$2");
    String withoutFields = SENSITIVE_FIELD.matcher(withoutText).replaceAll("$1" + REDACTED);
    String withoutRawApiToken = RAW_API_TOKEN.matcher(withoutFields).replaceAll(REDACTED);
    String maskedMobile = CHINESE_MOBILE.matcher(withoutRawApiToken).replaceAll("$1****$2");
    String maskedId = CHINESE_ID.matcher(maskedMobile).replaceAll("$1********$2");
    return EMAIL.matcher(maskedId).replaceAll(REDACTED);
  }
}
