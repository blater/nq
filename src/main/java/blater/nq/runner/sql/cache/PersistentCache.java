package blater.nq.runner.sql.cache;

import blater.jname.Jname;
import blater.jname.JnameOptions;
import blater.nq.cli.CacheName;
import blater.nq.cli.CacheNameSelection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/*
 * Responsibility: Owns persistent --cache storage, cache listing,
 * and cache clearing operations.
 */
public final class PersistentCache {
  private static final String H2_DATABASE_SUFFIX = ".mv.db";
  private static final String TYPED_ACTIVE_CACHE_FILE = ".active";

  private PersistentCache() { }



  /** Allocates a fresh logical cache beneath the cache directory itself. */
  public static CachePreparation prepare(
      Path cacheDirectory,
      CacheNameSelection nameSelection) {
    Path root = directCacheRoot(cacheDirectory);
    createTypedDirectories(root);
    return switch (nameSelection) {
      case CacheNameSelection.Generated ignored -> prepareGenerated(root);
      case CacheNameSelection.Named named -> prepareNamed(root, named.name());
    };
  }

  /**
   * Loads a new cache strictly, activating it only after the loader completes.
   * Any failed load removes all artifacts belonging to the new cache.
   */
  public static CacheHandle loadAndActivate(
      Path cacheDirectory,
      CacheNameSelection nameSelection,
      CacheLoader loader) {
    Objects.requireNonNull(loader, "loader");
    CachePreparation preparation = prepare(cacheDirectory, nameSelection);
    CacheHandle handle = preparation.handle();
    try {
      loader.load(handle);
      if (!isTypedCacheFile(handle.cacheFile())) {
        throw new IllegalStateException(
            "Cache loader completed without creating " + handle.cacheFile());
      }
      preparation.close();
      activate(handle, cacheDirectory);
      return handle;
    } catch (RuntimeException | Error failure) {
      try {
        deleteTypedCache(handle.cacheFile());
      } catch (RuntimeException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      try {
        preparation.close();
      } catch (RuntimeException releaseFailure) {
        failure.addSuppressed(releaseFailure);
      }
      throw failure;
    }
  }

  /** Activates an existing cache using an atomic sibling-file replacement. */
  public static void activate(CacheHandle handle, Path cacheDirectory) {
    Objects.requireNonNull(handle, "handle");
    Path root = directCacheRoot(cacheDirectory);
    CacheName name = logicalName(root, handle.cacheFile());
    if (!isTypedCacheFile(handle.cacheFile())) {
      throw new IllegalArgumentException("No existing cache found for [" + name.value() + "]");
    }
    writeTypedActiveSelection(root, name);
  }

  /** Selects and activates one existing logical cache. */
  public static CacheHandle use(CacheName name, Path cacheDirectory) {
    CacheHandle handle = select(name, cacheDirectory);
    activate(handle, cacheDirectory);
    return handle;
  }

  /** Selects one existing logical cache without changing the active cache. */
  public static CacheHandle select(CacheName name, Path cacheDirectory) {
    Objects.requireNonNull(name, "name");
    Path cacheFile = logicalCacheFile(directCacheRoot(cacheDirectory), name);
    if (!isTypedCacheFile(cacheFile)) {
      throw new IllegalArgumentException("No existing cache found for [" + name.value() + "]");
    }
    return currentHandle(cacheFile);
  }

  private static CacheHandle currentHandle(Path cacheFile) {
    return new CacheHandle(cacheFile, jdbcUrl(cacheFile), false);
  }

  /** Resolves the active cache without creating an absent cache directory. */
  public static CacheLookup active(Path cacheDirectory) {
    Path root = directCacheRoot(cacheDirectory);
    Path activeFile = root.resolve(TYPED_ACTIVE_CACHE_FILE);
    if (!Files.isRegularFile(activeFile)) {
      return new CacheLookup.None();
    }

    CacheName name;
    try {
      name = new CacheName(Files.readString(activeFile, StandardCharsets.UTF_8).trim());
    } catch (IOException | IllegalArgumentException failure) {
      deleteTypedActiveSelection(root);
      return new CacheLookup.None();
    }
    Path cacheFile = logicalCacheFile(root, name);
    if (!isTypedCacheFile(cacheFile)) {
      deleteTypedActiveSelection(root);
      return new CacheLookup.None();
    }
    return new CacheLookup.Found(name, currentHandle(cacheFile));
  }

  /** Lists logical caches without opening databases or creating directories. */
  public static List<LogicalCacheEntry> listCaches(Path cacheDirectory) {
    Path root = directCacheRoot(cacheDirectory);
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    CacheLookup active = active(root);
    List<LogicalCacheEntry> entries = new ArrayList<>();
    try (Stream<Path> paths = Files.list(root)) {
      paths.filter(PersistentCache::isTypedCacheFile).sorted().forEach(cacheFile -> {
        CacheName name = cacheNameFromFile(cacheFile);
        if (name != null) {
          entries.add(new LogicalCacheEntry(
              name,
              typedModifiedMillis(cacheFile),
              active instanceof CacheLookup.Found found && found.name().equals(name)));
        }
      });
      return List.copyOf(entries);
    } catch (IOException failure) {
      throw new IllegalStateException("Could not list cache files: " + root, failure);
    }
  }

  /** Clears a logical cache idempotently. */
  public static int clearNamed(CacheName name, Path cacheDirectory) {
    Objects.requireNonNull(name, "name");
    Path root = directCacheRoot(cacheDirectory);
    Path cacheFile = logicalCacheFile(root, name);
    boolean existed = isTypedCacheFile(cacheFile);
    deleteTypedCache(cacheFile);
    clearTypedActiveIfNamed(root, name);
    return existed ? 1 : 0;
  }

  /** Clears every logical cache and the active selection. */
  public static int clearAll(Path cacheDirectory) {
    Path root = directCacheRoot(cacheDirectory);
    List<LogicalCacheEntry> entries = listCaches(root);
    for (LogicalCacheEntry entry : entries) {
      deleteTypedCache(logicalCacheFile(root, entry.name()));
    }
    deleteTypedActiveSelection(root);
    return entries.size();
  }

  /** Clears logical caches strictly older than the supplied age. */
  public static int clearOlderThan(Duration duration, Path cacheDirectory) {
    Objects.requireNonNull(duration, "duration");
    if (duration.isNegative()) {
      throw new IllegalArgumentException("Cache age cannot be negative");
    }
    Path root = directCacheRoot(cacheDirectory);
    long cutoffMillis = Instant.now().minus(duration).toEpochMilli();
    int cleared = 0;
    for (LogicalCacheEntry entry : listCaches(root)) {
      if (entry.modifiedMillis() < cutoffMillis) {
        deleteTypedCache(logicalCacheFile(root, entry.name()));
        clearTypedActiveIfNamed(root, entry.name());
        cleared++;
      }
    }
    return cleared;
  }

  /** Performs the engine-specific population step for a newly allocated cache. */
  @FunctionalInterface
  public interface CacheLoader {
    void load(CacheHandle handle);
  }

  public static Duration parseDuration(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("cache age duration is required");
    }
    String normalized = value.trim().toLowerCase();
    int split = 0;
    while (split < normalized.length() && Character.isDigit(normalized.charAt(split))) {
      split++;
    }
    if (split == 0 || split == normalized.length()) {
      throw new IllegalArgumentException("Unsupported cache age duration: " + value);
    }
    long amount = Long.parseLong(normalized.substring(0, split));
    String unit = normalized.substring(split).trim();
    return switch (unit) {
      case "m", "min", "mins", "minute", "minutes" -> Duration.ofMinutes(amount);
      case "h", "hr", "hrs", "hour", "hours" -> Duration.ofHours(amount);
      case "d", "day", "days" -> Duration.ofDays(amount);
      default -> throw new IllegalArgumentException("Unsupported cache age duration: " + value);
    };
  }


  private static String generateCacheName() {
    return Jname.generate(JnameOptions.builder()
        .words(2)
        .maxLetters(8)
        .build());
  }

  private static CachePreparation prepareGenerated(Path root) {
    while (true) {
      CacheName name = new CacheName(generateCacheName());
      Path cacheFile = logicalCacheFile(root, name);
      if (!typedCacheArtifactsExist(cacheFile)) {
        switch (tryClaim(cacheFile)) {
          case ClaimAttempt.Claimed claimed -> {
            return claimed.preparation();
          }
          case ClaimAttempt.Collision ignored -> {
          }
        }
      }
    }
  }

  private static CachePreparation prepareNamed(Path root, CacheName name) {
    Path cacheFile = logicalCacheFile(root, name);
    if (typedCacheArtifactsExist(cacheFile)) {
      throw new IllegalArgumentException("Cache already exists: " + name.value());
    }
    CachePreparation claimed = switch (tryClaim(cacheFile)) {
      case ClaimAttempt.Claimed acquired -> acquired.preparation();
      case ClaimAttempt.Collision ignored -> throw new IllegalArgumentException(
          "Cache is already being created: " + name.value());
    };
    if (typedCacheArtifactsExist(cacheFile)) {
      claimed.close();
      throw new IllegalArgumentException("Cache already exists: " + name.value());
    }
    return claimed;
  }

  private static ClaimAttempt tryClaim(Path cacheFile) {
    Path claimFile = Path.of(typedDatabasePath(cacheFile) + ".claim");
    try {
      Files.writeString(
          claimFile,
          "nq cache creation in progress" + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE);
      return new ClaimAttempt.Claimed(new CachePreparation(
          new CacheHandle(cacheFile, jdbcUrl(cacheFile), true), claimFile));
    } catch (java.nio.file.FileAlreadyExistsException collision) {
      return new ClaimAttempt.Collision();
    } catch (IOException failure) {
      throw new IllegalStateException("Could not claim cache name: " + cacheFile, failure);
    }
  }

  private sealed interface ClaimAttempt permits ClaimAttempt.Claimed, ClaimAttempt.Collision {
    record Claimed(CachePreparation preparation) implements ClaimAttempt {
      public Claimed {
        Objects.requireNonNull(preparation, "preparation");
      }
    }

    record Collision() implements ClaimAttempt {
    }
  }

  private static Path directCacheRoot(Path cacheDirectory) {
    Objects.requireNonNull(cacheDirectory, "cacheDirectory");
    return cacheDirectory.toAbsolutePath().normalize();
  }

  private static Path logicalCacheFile(Path root, CacheName name) {
    return root.resolve(name.value() + H2_DATABASE_SUFFIX);
  }

  private static CacheName logicalName(Path root, Path cacheFile) {
    Path normalizedFile = cacheFile.toAbsolutePath().normalize();
    if (!root.equals(normalizedFile.getParent())) {
      throw new IllegalArgumentException("Cache is outside the selected cache directory: " + cacheFile);
    }
    CacheName name = cacheNameFromFile(normalizedFile);
    if (name == null) {
      throw new IllegalArgumentException("Invalid cache filename: " + cacheFile);
    }
    return name;
  }

  private static CacheName cacheNameFromFile(Path cacheFile) {
    String filename = cacheFile.getFileName().toString();
    if (!filename.endsWith(H2_DATABASE_SUFFIX)) {
      return null;
    }
    try {
      return new CacheName(filename.substring(0, filename.length() - H2_DATABASE_SUFFIX.length()));
    } catch (IllegalArgumentException failure) {
      return null;
    }
  }

  private static boolean isTypedCacheFile(Path cacheFile) {
    return Files.isRegularFile(cacheFile) && cacheNameFromFile(cacheFile) != null;
  }

  private static boolean typedCacheArtifactsExist(Path cacheFile) {
    String base = typedDatabasePath(cacheFile);
    return Stream.of(".mv.db", ".trace.db", ".lock.db", ".temp.db", ".newFile", ".tempFile")
        .map(suffix -> Path.of(base + suffix))
        .anyMatch(Files::exists);
  }

  private static long typedModifiedMillis(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException failure) {
      throw new IllegalStateException("Could not read cache timestamp: " + path, failure);
    }
  }

  private static String typedDatabasePath(Path cacheFile) {
    String path = cacheFile.toAbsolutePath().normalize().toString();
    if (!path.endsWith(H2_DATABASE_SUFFIX)) {
      throw new IllegalArgumentException("Invalid H2 cache filename: " + cacheFile);
    }
    return path.substring(0, path.length() - H2_DATABASE_SUFFIX.length());
  }

  private static String jdbcUrl(Path cacheFile) {
    return "jdbc:h2:file:" + typedDatabasePath(cacheFile)
        + ";MODE=MySQL;NON_KEYWORDS=VALUE";
  }

  private static void createTypedDirectories(Path directory) {
    try {
      Files.createDirectories(directory);
    } catch (IOException failure) {
      throw new IllegalStateException("Could not create cache directory: " + directory, failure);
    }
  }

  private static void writeTypedActiveSelection(Path root, CacheName name) {
    createTypedDirectories(root);
    Path activeFile = root.resolve(TYPED_ACTIVE_CACHE_FILE);
    Path temporary = null;
    try {
      temporary = Files.createTempFile(root, ".active-", ".tmp");
      Files.writeString(
          temporary,
          name.value() + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.TRUNCATE_EXISTING);
      Files.move(
          temporary,
          activeFile,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
      temporary = null;
    } catch (AtomicMoveNotSupportedException unsupported) {
      try {
        Files.move(temporary, activeFile, StandardCopyOption.REPLACE_EXISTING);
        temporary = null;
      } catch (IOException fallbackFailure) {
        fallbackFailure.addSuppressed(unsupported);
        throw new IllegalStateException("Could not update active cache: " + activeFile, fallbackFailure);
      }
    } catch (IOException failure) {
      throw new IllegalStateException("Could not update active cache: " + activeFile, failure);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // The active-selection failure remains the primary error.
        }
      }
    }
  }

  private static void clearTypedActiveIfNamed(Path root, CacheName name) {
    CacheLookup selected = active(root);
    if (selected instanceof CacheLookup.Found found && found.name().equals(name)) {
      deleteTypedActiveSelection(root);
    }
  }

  private static void deleteTypedActiveSelection(Path root) {
    try {
      Files.deleteIfExists(root.resolve(TYPED_ACTIVE_CACHE_FILE));
    } catch (IOException failure) {
      throw new IllegalStateException("Could not clear active cache selection: " + root, failure);
    }
  }

  private static void deleteTypedCache(Path cacheFile) {
    String base = typedDatabasePath(cacheFile);
    RuntimeException firstFailure = null;
    for (String suffix : List.of(
        ".mv.db", ".trace.db", ".lock.db", ".temp.db", ".newFile", ".tempFile")) {
      try {
        Files.deleteIfExists(Path.of(base + suffix));
      } catch (IOException failure) {
        IllegalStateException wrapped = new IllegalStateException(
            "Could not delete cache path: " + Path.of(base + suffix), failure);
        if (firstFailure == null) {
          firstFailure = wrapped;
        } else {
          firstFailure.addSuppressed(wrapped);
        }
      }
    }
    if (firstFailure != null) {
      throw firstFailure;
    }
  }


}
