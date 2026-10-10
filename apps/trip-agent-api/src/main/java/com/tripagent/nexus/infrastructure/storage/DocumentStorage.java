package com.tripagent.nexus.infrastructure.storage;

import com.tripagent.nexus.common.error.ApiException;
import com.tripagent.nexus.infrastructure.config.UploadProperties;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class DocumentStorage {

  private final Path root;

  public DocumentStorage(UploadProperties properties) {
    this.root = properties.getStorageRoot().toAbsolutePath().normalize();
  }

  @PostConstruct
  void initialize() {
    try {
      Files.createDirectories(root);
      setDirectoryPermissions(root);
    } catch (IOException exception) {
      throw new IllegalStateException(
          "Could not initialize controlled document storage", exception);
    }
  }

  public String store(UUID tripId, UUID documentId, byte[] content) {
    String storageKey = tripId + "/" + documentId;
    Path target = resolve(storageKey);
    try {
      Files.createDirectories(target.getParent());
      setDirectoryPermissions(target.getParent());
      Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
      setFilePermissions(target);
      return storageKey;
    } catch (IOException exception) {
      throw new ApiException(
          "DOCUMENT_STORAGE_FAILED",
          HttpStatus.INTERNAL_SERVER_ERROR,
          "The document could not be stored.");
    }
  }

  public byte[] read(String storageKey) {
    try {
      return Files.readAllBytes(resolve(storageKey));
    } catch (IOException exception) {
      throw new ApiException(
          "DOCUMENT_STORAGE_READ_FAILED",
          HttpStatus.INTERNAL_SERVER_ERROR,
          "The stored document could not be read.");
    }
  }

  public void deleteQuietly(String storageKey) {
    try {
      Files.deleteIfExists(resolve(storageKey));
    } catch (IOException ignored) {
      // Orphan cleanup can retry independently without masking the original transaction failure.
    }
  }

  private Path resolve(String storageKey) {
    Path resolved = root.resolve(storageKey).normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("DOCUMENT_STORAGE_KEY_INVALID");
    }
    return resolved;
  }

  private static void setDirectoryPermissions(Path path) {
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException | IOException ignored) {
      // POSIX permissions are unavailable on some supported development filesystems.
    }
  }

  private static void setFilePermissions(Path path) {
    try {
      Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException | IOException ignored) {
      // POSIX permissions are unavailable on some supported development filesystems.
    }
  }
}
