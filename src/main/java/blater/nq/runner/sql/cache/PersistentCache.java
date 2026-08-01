package blater.nq.runner.sql.cache;

import blater.jname.Jname;
import blater.jname.JnameOptions;
import blater.nq.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static blater.nq.ParameterParser.*;

/*
 * Responsibility: Owns persistent --cache storage, cache listing,
 * and cache clearing operations.
 */
public final class PersistentCache {
  private static final String H2_DATABASE_SUFFIX = ".mv.db";
  private static final String ACTIVE_CACHE_FILE = "active.cache.file";

  private PersistentCache() { }


  public static int clear(Map<String, String> params) {
    int cnt = 0;
    if (params.containsKey(CACHE_CLEAR_TARGET_PARAM)) {
      cnt = PersistentCache.clearNamed(params.get(CACHE_CLEAR_TARGET_PARAM), params);
    }
    else if (params.containsKey(CACHE_CLEAR_OLDER_THAN_PARAM)) {
      Duration duration = PersistentCache.parseDuration(params.get(CACHE_CLEAR_OLDER_THAN_PARAM));
      cnt = PersistentCache.clearOlderThan(duration, params);
    }
    else {
      cnt = PersistentCache.clearAll(params);
    }
    Log.info("Cleared " + cnt + " cache(s).");
    return cnt;
  }

  public static void list(Map<String, String> params) {
    System.out.println("Cache root: " + PersistentCache.cacheRoot(params));
    var entries = listCaches(params);
    if (entries.isEmpty()) {
      Log.info("No caches found.");
      return;
    }

    Log.info("  name\tmodified");
    for (var entry : entries) {
      Log.info((entry.active() ? "* " : "  ") + entry.cacheFilename()
          + "\t" + Instant.ofEpochMilli(entry.modifiedMillis()));
    }
  }


  public static CacheHandle prepare(Map<String, String> params) {
    Path root = cacheRoot(params);
    createDirectories(root);
    Path cacheFile = unusedCacheFile(root, PersistentCache::generateCacheName);
    return new CacheHandle(cacheFile, jdbcUrl(cacheFile), true);
  }

  public static void activate(CacheHandle handle) {
    configureActiveCacheFile(handle.cacheFile());
  }

  public static CacheHandle use(String target, Map<String, String> params) {
    Optional<Path> namedCacheFile = resolveCacheFilename(target, params);
    if (namedCacheFile.isEmpty()) {
      return Log.fatal(IllegalArgumentException.class,
          "--use-cache requires a cache filename such as bright-otter.mv.db.");
    }
    Path cacheFile = namedCacheFile.get();
    if (!isCacheFile(cacheFile)) {
      return Log.fatal(IllegalArgumentException.class,
          "No existing cache found at " + cacheFile + ".");
    }
    CacheHandle handle = currentHandle(cacheFile);
    activate(handle);
    return handle;
  }

  private static CacheHandle currentHandle(Path cacheFile) {
    return new CacheHandle(cacheFile, jdbcUrl(cacheFile), false);
  }

  public static Optional<CacheHandle> active() {
    Optional<Path> configured = configuredActiveCacheFile();
    if (configured.isEmpty()) {
      return Optional.empty();
    }

    Path cacheFile = configured.get();
    if (!isCacheFile(cacheFile)) {
      clearActiveSelection();
      return Optional.empty();
    }
    return Optional.of(new CacheHandle(cacheFile, jdbcUrl(cacheFile), false));
  }

  static int clearAll(Map<String, String> params) {
    int cleared = 0;
    for (Path cacheFile : cacheFiles(params)) {
      deleteCache(cacheFile);
      cleared++;
    }
    clearActiveIfMissing();
    return cleared;
  }

  static List<CacheEntry> listCaches(Map<String, String> params) {
    Optional<Path> activeCache = configuredActiveCacheFile();
    return cacheFiles(params).stream()
        .map(cacheFile -> new CacheEntry(
            cacheFile.getFileName().toString(),
            modifiedMillis(cacheFile),
            activeCache.map(cacheFile::equals).orElse(false)))
        .toList();
  }

  public static int clearNamed(String target, Map<String, String> params) {
    Optional<Path> namedCacheFile = resolveCacheFilename(target, params);
    if (namedCacheFile.isEmpty()) {
      return Log.fatal(IllegalArgumentException.class,
          "--clear-cache requires a cache filename such as bright-otter.mv.db.");
    }
    Path cacheFile = namedCacheFile.get();
    if (!isCacheFile(cacheFile)) {
      return 0;
    }
    deleteCache(cacheFile);
    clearActiveIfMissing();
    return 1;
  }

  public static int clearOlderThan(Duration duration, Map<String, String> params) {
    long cutoffMillis = Instant.now().minus(duration).toEpochMilli();
    int cleared = 0;
    for (Path cacheFile : cacheFiles(params)) {
      if (modifiedMillis(cacheFile) < cutoffMillis) {
        deleteCache(cacheFile);
        cleared++;
      }
    }
    clearActiveIfMissing();
    return cleared;
  }

  public static Duration parseDuration(String value) {
    if (value == null || value.isBlank()) {
      return Log.fatal(IllegalArgumentException.class, "cache age duration is required");
    }
    String normalized = value.trim().toLowerCase();
    int split = 0;
    while (split < normalized.length() && Character.isDigit(normalized.charAt(split))) {
      split++;
    }
    if (split == 0 || split == normalized.length()) {
      return Log.fatal(IllegalArgumentException.class, "Unsupported cache age duration: " + value);
    }
    long amount = Long.parseLong(normalized.substring(0, split));
    String unit = normalized.substring(split).trim();
    return switch (unit) {
      case "m", "min", "mins", "minute", "minutes" -> Duration.ofMinutes(amount);
      case "h", "hr", "hrs", "hour", "hours" -> Duration.ofHours(amount);
      case "d", "day", "days" -> Duration.ofDays(amount);
      default -> Log.fatal(IllegalArgumentException.class, "Unsupported cache age duration: " + value);
    };
  }

  public static Path cacheRoot(Map<String, String> params) {
    String configured = params == null ? null : params.get(CACHE_DIR_PARAM);
    if (configured == null || configured.isBlank()) {
      configured = Path.of(System.getProperty("user.home"), ".nq", "cache").toString();
    }
    return Path.of(configured).toAbsolutePath().normalize();
  }

  static Path unusedCacheFile(Path root, Supplier<String> names) {
    while (true) {
      String name = names.get();
      Path candidate = root.resolve(name + H2_DATABASE_SUFFIX);
      if (!Files.exists(candidate)) {
        return candidate;
      }
    }
  }

  private static String generateCacheName() {
    return Jname.generate(JnameOptions.builder()
        .words(2)
        .maxLetters(8)
        .build());
  }

  static Path configFile() {
    return Path.of(System.getProperty("user.home"), ".nq", "config.properties")
        .toAbsolutePath()
        .normalize();
  }

  private static String jdbcUrl(Path cacheFile) {
    return "jdbc:h2:file:" + databasePath(cacheFile) + ";MODE=MySQL;NON_KEYWORDS=VALUE";
  }

  private static String databasePath(Path cacheFile) {
    String path = cacheFile.toAbsolutePath().normalize().toString();
    if (!path.endsWith(H2_DATABASE_SUFFIX)) {
      return Log.fatal(IllegalArgumentException.class, "Invalid H2 cache filename: " + cacheFile);
    }
    return path.substring(0, path.length() - H2_DATABASE_SUFFIX.length());
  }

  private static List<Path> cacheFiles(Map<String, String> params) {
    Path root = cacheRoot(params);
    if (!Files.exists(root)) {
      return List.of();
    }

    try (Stream<Path> paths = Files.list(root)) {
      return paths.filter(PersistentCache::isCacheFile).sorted().toList();
    } catch (IOException ex) {
      return Log.fatal(IllegalStateException.class, "Could not list cache files: " + root, ex);
    }
  }

  private static boolean isCacheFile(Path path) {
    String filename = path.getFileName().toString();
    return Files.isRegularFile(path)
        && isCacheFilename(filename);
  }

  private static long modifiedMillis(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException ex) {
      return Log.fatal(IllegalStateException.class, "Could not read cache timestamp: " + path, ex);
    }
  }

  private static Optional<Path> resolveCacheFilename(
      String target,
      Map<String, String> params) {
    if (target == null || target.isBlank()) {
      return Optional.empty();
    }
    Path path = Path.of(target);
    if (path.getNameCount() != 1 || !isCacheFilename(path.toString())) {
      return Optional.empty();
    }
    return Optional.of(cacheRoot(params).resolve(path).toAbsolutePath().normalize());
  }

  private static boolean isCacheFilename(String filename) {
    return filename.length() > H2_DATABASE_SUFFIX.length()
        && filename.endsWith(H2_DATABASE_SUFFIX);
  }

  private static Optional<Path> configuredActiveCacheFile() {
    Properties config = readConfig();
    String value = config.getProperty(ACTIVE_CACHE_FILE);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }

    try {
      return Optional.of(Path.of(value).toAbsolutePath().normalize());
    } catch (RuntimeException ex) {
      clearActiveSelection();
      return Optional.empty();
    }
  }

  private static void configureActiveCacheFile(Path cacheFile) {
    Properties config = readConfig();
    config.setProperty(ACTIVE_CACHE_FILE, cacheFile.toAbsolutePath().normalize().toString());
    writeConfig(config);
  }

  private static Properties readConfig() {
    Properties config = new Properties();
    Path file = configFile();
    if (!Files.exists(file)) {
      return config;
    }
    try (InputStream input = Files.newInputStream(file)) {
      config.load(input);
      return config;
    } catch (IOException ex) {
      return Log.fatal(IllegalStateException.class, "Could not read nq configuration: " + file, ex);
    }
  }

  private static void writeConfig(Properties config) {
    Path file = configFile();
    try {
      Files.createDirectories(file.getParent());
      try (OutputStream output = Files.newOutputStream(file)) {
        config.store(output, "nq configuration");
      }
    } catch (IOException ex) {
      Log.fatal(IllegalStateException.class, "Could not write nq configuration: " + file, ex);
    }
  }

  private static void clearActiveIfMissing() {
    Optional<Path> cache = configuredActiveCacheFile();
    if (cache.isPresent() && !Files.exists(cache.get())) {
      clearActiveSelection();
    }
  }

  private static void clearActiveSelection() {
    Properties config = readConfig();
    boolean changed = config.remove(ACTIVE_CACHE_FILE) != null;
    if (!changed) {
      return;
    }

    Path file = configFile();
    if (config.isEmpty()) {
      try {
        Files.deleteIfExists(file);
      } catch (IOException ex) {
        Log.fatal(IllegalStateException.class, "Could not update nq configuration: " + file, ex);
      }
    } else {
      writeConfig(config);
    }
  }

  private static void createDirectories(Path dir) {
    try {
      Files.createDirectories(dir);
    } catch (IOException ex) {
      Log.fatal(IllegalStateException.class, "Could not create cache directory: " + dir, ex);
    }
  }

  private static void deleteCache(Path cacheFile) {
    String base = databasePath(cacheFile);
    Stream.of(".mv.db", ".trace.db", ".lock.db", ".temp.db", ".newFile", ".tempFile")
        .map(suffix -> Path.of(base + suffix))
        .forEach(PersistentCache::deletePath);
  }

  private static void deletePath(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ex) {
      Log.fatal(IllegalStateException.class, "Could not delete cache path: " + path, ex);
    }
  }

}
