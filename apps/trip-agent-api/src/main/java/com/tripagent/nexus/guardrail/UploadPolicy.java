package com.tripagent.nexus.guardrail;

import com.tripagent.nexus.infrastructure.config.UploadProperties;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.tika.Tika;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class UploadPolicy {

  private static final Set<String> EXECUTABLE_MIME_TYPES =
      Set.of(
          "application/x-dosexec",
          "application/x-executable",
          "application/x-mach-binary",
          "application/x-sharedlib");
  private static final Map<String, Set<String>> MIME_EXTENSIONS =
      Map.of(
          "image/jpeg", Set.of(".jpg", ".jpeg"),
          "image/png", Set.of(".png"),
          "image/webp", Set.of(".webp"),
          "application/pdf", Set.of(".pdf"));

  private final long maxSizeBytes;
  private final long maxPixels;
  private final int maxPdfPages;
  private final Set<String> allowedMimeTypes;
  private final Set<String> allowedExtensions;
  private final Tika tika = new Tika();

  public UploadPolicy(UploadProperties properties) {
    this.maxSizeBytes = properties.getMaxSize().toBytes();
    this.maxPixels = properties.getMaxPixels();
    this.maxPdfPages = properties.getMaxPdfPages();
    this.allowedMimeTypes =
        properties.getAllowedMimeTypes().stream()
            .map(UploadPolicy::normalize)
            .collect(Collectors.toUnmodifiableSet());
    this.allowedExtensions =
        properties.getAllowedExtensions().stream()
            .map(UploadPolicy::normalizeExtension)
            .collect(Collectors.toUnmodifiableSet());
  }

  public void validate(String originalFilename, String contentType, long sizeBytes) {
    if (sizeBytes <= 0 || sizeBytes > maxSizeBytes) {
      throw new UploadPolicyViolation("UPLOAD_SIZE_INVALID");
    }
    if (contentType == null || !allowedMimeTypes.contains(normalize(contentType))) {
      throw new UploadPolicyViolation("UPLOAD_MIME_TYPE_DENIED");
    }

    String extension = StringUtils.getFilenameExtension(originalFilename);
    if (extension == null || !allowedExtensions.contains(normalizeExtension(extension))) {
      throw new UploadPolicyViolation("UPLOAD_EXTENSION_DENIED");
    }
  }

  public UploadInspection inspect(String originalFilename, String contentType, byte[] content) {
    validate(originalFilename, contentType, content.length);
    rejectExecutableSignature(content);
    String actualMediaType = detectMediaType(originalFilename, content);
    if (EXECUTABLE_MIME_TYPES.contains(actualMediaType)) {
      throw new UploadPolicyViolation("UPLOAD_EXECUTABLE_DENIED");
    }
    if (!allowedMimeTypes.contains(actualMediaType)) {
      throw new UploadPolicyViolation("UPLOAD_MIME_TYPE_DENIED");
    }

    String extension =
        "." + StringUtils.getFilenameExtension(originalFilename).toLowerCase(Locale.ROOT);
    if (!actualMediaType.equals(normalize(contentType))
        || !MIME_EXTENSIONS.getOrDefault(actualMediaType, Set.of()).contains(extension)) {
      throw new UploadPolicyViolation("UPLOAD_MIME_MISMATCH");
    }

    if ("application/pdf".equals(actualMediaType)) {
      return inspectPdf(actualMediaType, content);
    }
    return inspectImage(actualMediaType, content);
  }

  private UploadInspection inspectPdf(String actualMediaType, byte[] content) {
    try (PDDocument document = Loader.loadPDF(content)) {
      int pageCount = document.getNumberOfPages();
      if (pageCount < 1) {
        throw new UploadPolicyViolation("UPLOAD_FILE_MALFORMED");
      }
      if (pageCount > maxPdfPages) {
        throw new UploadPolicyViolation("UPLOAD_PAGE_LIMIT_EXCEEDED");
      }
      for (int index = 0; index < pageCount; index++) {
        var box = document.getPage(index).getCropBox();
        long width = Math.max(1L, Math.round(box.getWidth() * 300D / 72D));
        long height = Math.max(1L, Math.round(box.getHeight() * 300D / 72D));
        rejectExcessivePixels(width, height);
      }
      return new UploadInspection(actualMediaType, pageCount);
    } catch (UploadPolicyViolation exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw new UploadPolicyViolation("UPLOAD_FILE_MALFORMED");
    }
  }

  private UploadInspection inspectImage(String actualMediaType, byte[] content) {
    try (ByteArrayInputStream input = new ByteArrayInputStream(content);
        ImageInputStream imageInput = ImageIO.createImageInputStream(input)) {
      if (imageInput == null) {
        throw new UploadPolicyViolation("UPLOAD_FILE_MALFORMED");
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
      if (!readers.hasNext()) {
        throw new UploadPolicyViolation("UPLOAD_FILE_MALFORMED");
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(imageInput, true, true);
        rejectExcessivePixels(reader.getWidth(0), reader.getHeight(0));
      } finally {
        reader.dispose();
      }
      return new UploadInspection(actualMediaType, 1);
    } catch (UploadPolicyViolation exception) {
      throw exception;
    } catch (IOException | RuntimeException exception) {
      throw new UploadPolicyViolation("UPLOAD_FILE_MALFORMED");
    }
  }

  private void rejectExcessivePixels(long width, long height) {
    if (width <= 0 || height <= 0 || width > maxPixels || height > maxPixels) {
      throw new UploadPolicyViolation("UPLOAD_PIXEL_LIMIT_EXCEEDED");
    }
    if (width > maxPixels / height) {
      throw new UploadPolicyViolation("UPLOAD_PIXEL_LIMIT_EXCEEDED");
    }
  }

  private String detectMediaType(String originalFilename, byte[] content) {
    try {
      return normalize(tika.detect(content, originalFilename));
    } catch (RuntimeException exception) {
      throw new UploadPolicyViolation("UPLOAD_FILE_MALFORMED");
    }
  }

  private static void rejectExecutableSignature(byte[] content) {
    if (startsWith(content, 0x4d, 0x5a)
        || startsWith(content, 0x7f, 0x45, 0x4c, 0x46)
        || startsWith(content, 0xca, 0xfe, 0xba, 0xbe)
        || startsWith(content, 0xcf, 0xfa, 0xed, 0xfe)
        || startsWith(content, 0xfe, 0xed, 0xfa, 0xcf)) {
      throw new UploadPolicyViolation("UPLOAD_EXECUTABLE_DENIED");
    }
  }

  private static boolean startsWith(byte[] content, int... signature) {
    if (content.length < signature.length) {
      return false;
    }
    for (int index = 0; index < signature.length; index++) {
      if (Byte.toUnsignedInt(content[index]) != signature[index]) {
        return false;
      }
    }
    return true;
  }

  private static String normalize(String value) {
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private static String normalizeExtension(String value) {
    String normalized = normalize(value);
    return normalized.startsWith(".") ? normalized : "." + normalized;
  }

  public static final class UploadPolicyViolation extends RuntimeException {

    public UploadPolicyViolation(String code) {
      super(code);
    }
  }

  public record UploadInspection(String mediaType, int pageCount) {}
}
