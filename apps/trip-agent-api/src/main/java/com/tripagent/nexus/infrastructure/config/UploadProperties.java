package com.tripagent.nexus.infrastructure.config;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.upload")
public class UploadProperties {

  @NotNull private DataSize maxSize = DataSize.ofMegabytes(10);

  @NotNull private Path storageRoot = Path.of("./data/uploads");

  @Positive private long maxPixels = 40_000_000L;

  @Positive private int maxPdfPages = 20;

  @NotEmpty private Set<String> allowedMimeTypes = new LinkedHashSet<>();

  @NotEmpty private Set<String> allowedExtensions = new LinkedHashSet<>();

  public DataSize getMaxSize() {
    return maxSize;
  }

  public void setMaxSize(DataSize maxSize) {
    this.maxSize = maxSize;
  }

  public Path getStorageRoot() {
    return storageRoot;
  }

  public void setStorageRoot(Path storageRoot) {
    this.storageRoot = storageRoot;
  }

  public long getMaxPixels() {
    return maxPixels;
  }

  public void setMaxPixels(long maxPixels) {
    this.maxPixels = maxPixels;
  }

  public int getMaxPdfPages() {
    return maxPdfPages;
  }

  public void setMaxPdfPages(int maxPdfPages) {
    this.maxPdfPages = maxPdfPages;
  }

  public Set<String> getAllowedMimeTypes() {
    return Set.copyOf(allowedMimeTypes);
  }

  public void setAllowedMimeTypes(Set<String> allowedMimeTypes) {
    this.allowedMimeTypes = new LinkedHashSet<>(allowedMimeTypes);
  }

  public Set<String> getAllowedExtensions() {
    return Set.copyOf(allowedExtensions);
  }

  public void setAllowedExtensions(Set<String> allowedExtensions) {
    this.allowedExtensions = new LinkedHashSet<>(allowedExtensions);
  }
}
