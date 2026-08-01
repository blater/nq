package blater.nq.runner.sql.cache;

import blater.jname.Jname;
import blater.jname.JnameOptions;
import blater.nq.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
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
  private static final String CACHE_NAME_PATTERN = "[a-z0-9]{1,8}-[a-z0-9]{1,8}";
  private static final int METADATA_ROW_ID = 1;
  private static final String ACTIVE_CACHE_FILE = "active.cache.file";
  private static final int ARTIFACT_ROW_ID = 1;

  private PersistentCache() { }


  public static int clear(Map<String, String> params) {
    int cnt = 0;
    if (params.containsKey(CACHE_CLEAR_TARGET_PARAM)) {
      cnt = PersistentCache.clearForInput(params.get(CACHE_CLEAR_TARGET_PARAM), params);
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

    Log.info("  name\tinputType\tcreated\tvariant\tsource");
    for (var entry : entries) {
      Log.info((entry.active() ? "* " : "  ") + entry.cacheFilename()
          + "\t" + entry.inputType()
          + "\t" + ((entry.createdMillis()  <= 0) ? "-": Instant.ofEpochMilli(entry.createdMillis()).toString())
          + "\t" + entry.variantId()
          + (entry.outdated() ? " (outdated)" : "")
          + "\t" + entry.sourcePath());
    }
  }


  public static CacheHandle prepare(CacheSource source, Map<String, String> params)
  {
    Path root = cacheRoot(params);
    createDirectories(root);
    Optional<Path> existing = cacheFiles(params).stream()
        .filter(cacheFile -> readMetadata(cacheFile).filter(source::matches).isPresent())
        .findFirst();
    if (existing.isPresent()) {
      Path cacheFile = existing.get();
      return new CacheHandle(cacheFile, jdbcUrl(cacheFile), false, source);
    }

    Path cacheFile = unusedCacheFile(root, PersistentCache::generateCacheName);
    return new CacheHandle(cacheFile, jdbcUrl(cacheFile), true, source);
  }

  public static void markLoaded(CacheHandle handle) {
    writeMetadata(handle.cacheFile(), handle.source());
  }

  public static Optional<CachedArtifact> readArtifact(
      Path cacheFile,
      String identityText) {
    if (!Files.exists(cacheFile)) return Optional.empty();
    String sql = """
        select payload, artifact_version, refreshed_millis, expiry_hours
        from nq_internal.cache_artifact
        where id = ? and identity_text = ?
        """;
    try (Connection connection = connect(cacheFile, true);
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, ARTIFACT_ROW_ID);
      statement.setString(2, identityText);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) return Optional.empty();
        return Optional.of(new CachedArtifact(
            resultSet.getBytes("payload"),
            resultSet.getInt("artifact_version"),
            resultSet.getLong("refreshed_millis"),
            resultSet.getLong("expiry_hours")));
      }
    } catch (SQLException ex) {
      Log.debug("Could not read cached artifact [{}]: {}", cacheFile, ex.getMessage());
      return Optional.empty();
    }
  }

  public static boolean writeArtifact(
      Path cacheFile,
      String identityText,
      String displayName,
      int version,
      long expiryHours,
      byte[] payload) {
    try {
      Files.createDirectories(cacheFile.getParent());
    } catch (IOException ex) {
      Log.warn("Could not create database-structure cache directory [{}]: {}", cacheFile.getParent(), ex.getMessage());
      return false;
    }
    try (Connection connection = connect(cacheFile, false)) {
      connection.setAutoCommit(false);
      try {
        createMetadataTable(connection);
        createArtifactTable(connection);
        Optional<Metadata> existingMetadata = readMetadata(connection);
        if (existingMetadata.isEmpty()
            || "DATABASE_STRUCTURE".equals(existingMetadata.get().inputType())) {
          writeArtifactMetadata(connection, identityText, displayName);
        }
        try (PreparedStatement delete = connection.prepareStatement(
            "delete from nq_internal.cache_artifact where id = ?")) {
          delete.setInt(1, ARTIFACT_ROW_ID);
          delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement("""
            insert into nq_internal.cache_artifact (
              id, identity_text, artifact_version, refreshed_millis, expiry_hours, payload
            ) values (?, ?, ?, ?, ?, ?)
            """)) {
          insert.setInt(1, ARTIFACT_ROW_ID);
          insert.setString(2, identityText);
          insert.setInt(3, version);
          insert.setLong(4, Instant.now().toEpochMilli());
          insert.setLong(5, expiryHours);
          insert.setBytes(6, payload);
          insert.executeUpdate();
        }
        connection.commit();
      } catch (SQLException ex) {
        connection.rollback();
        throw ex;
      }
      return true;
    } catch (SQLException ex) {
      Log.warn("Could not write cached database structure [{}]: {}", cacheFile, ex.getMessage());
      return false;
    }
  }

  public static void setArtifactExpiry(
      Path cacheFile,
      String identityText,
      long expiryHours) {
    try (Connection connection = connect(cacheFile, true);
         PreparedStatement statement = connection.prepareStatement(
             "update nq_internal.cache_artifact set expiry_hours = ? where id = ? and identity_text = ?")) {
      statement.setLong(1, expiryHours);
      statement.setInt(2, ARTIFACT_ROW_ID);
      statement.setString(3, identityText);
      statement.executeUpdate();
    } catch (SQLException ex) {
      Log.warn("Could not update cached database structure expiry [{}]: {}", cacheFile, ex.getMessage());
    }
  }

  public static void activate(CacheHandle handle) {
    Properties config = readConfig();
    config.setProperty(ACTIVE_CACHE_FILE, handle.cacheFile().toAbsolutePath().normalize().toString());
    writeConfig(config);
  }

  public static CacheHandle use(String target, Map<String, String> params) {
    Optional<Path> namedCacheFile = resolveCacheFilename(target, params);
    if (namedCacheFile.isPresent()) {
      if (MaterializationConfiguration.from(params).explicitlyConfigured()) {
        return Log.fatal(IllegalArgumentException.class,
            "Materialization options cannot be combined with --use-cache <cache-file>.");
      }
      Path cacheFile = namedCacheFile.get();
      CacheHandle handle = readMetadata(cacheFile)
          .map(metadata -> currentHandle(cacheFile, metadata))
          .orElseGet(() -> Log.fatal(
              IllegalArgumentException.class,
              "No existing cache found at " + cacheFile + "."));
      activate(handle);
      return handle;
    }

    String sourcePath = CacheSource.normalizedSourceIdentity(target);
    boolean variantSpecified = params.containsKey(PARQUET_RECORD_PARAM)
        || MaterializationConfiguration.from(params).explicitlyConfigured();
    CacheSource requested = CacheSource.from(target, params);

    List<CacheHandle> sourceMatches = cacheFiles(params).stream()
        .map(cacheFile -> readMetadata(cacheFile)
            .filter(metadata -> !metadata.databaseStructure())
            .filter(metadata -> sourcePath.equals(metadata.sourcePath()))
            .map(metadata -> new CacheHandle(
                cacheFile, jdbcUrl(cacheFile), false, metadata.source())))
        .flatMap(Optional::stream)
        .toList();
    List<CacheHandle> matches = sourceMatches.stream()
        .filter(handle -> handle.source().currentLayout())
        .filter(handle -> !variantSpecified || requested.identityText().equals(handle.source().identityText()))
        .toList();

    if (matches.isEmpty()) {
      boolean hasCurrent = sourceMatches.stream().anyMatch(handle -> handle.source().currentLayout());
      if (!hasCurrent && sourceMatches.stream().anyMatch(handle -> !handle.source().currentLayout())) {
        return Log.fatal(
            IllegalArgumentException.class,
            "Existing cache layout is outdated for " + sourcePath + "; reload the source file.");
      }
      return Log.fatal(
          IllegalArgumentException.class,
          "No existing cache found for " + sourcePath + ".");
    }
    if (matches.size() > 1) {
      return Log.fatal(
          IllegalArgumentException.class,
          "Multiple caches found for " + sourcePath
              + "; specify --parquet-record and materialization options to select one. Variants: "
              + matches.stream().map(handle -> handle.source().displayVariantId())
                  .distinct().collect(java.util.stream.Collectors.joining(", ")) + ".");
    }

    CacheHandle handle = matches.getFirst();
    activate(handle);
    return handle;
  }

  private static CacheHandle currentHandle(Path cacheFile, Metadata metadata) {
    if (metadata.databaseStructure()) {
      return Log.fatal(IllegalArgumentException.class,
          "Cache file is a database-structure metadata cache, not an input cache: " + cacheFile + ".");
    }
    CacheSource source = metadata.source();
    if (!source.currentLayout()) {
      return Log.fatal(IllegalArgumentException.class,
          "Cache layout is outdated; reload " + source.sourcePath() + ".");
    }
    return new CacheHandle(cacheFile, jdbcUrl(cacheFile), false, source);
  }

  public static Optional<CacheHandle> active() {
    Optional<Path> configured = configuredActiveCacheFile();
    if (configured.isEmpty()) {
      return Optional.empty();
    }

    Path cacheFile = configured.get();
    Optional<Metadata> metadata = readMetadata(cacheFile);
    if (metadata.isEmpty()) {
      clearActiveSelection();
      return Optional.empty();
    }

    Metadata selected = metadata.get();
    if (selected.databaseStructure()) {
      clearActiveSelection();
      return Log.fatal(
          IllegalStateException.class,
          "The active cache is database-structure metadata, not an input cache.");
    }
    CacheSource source;
    try {
      source = selected.source();
    } catch (RuntimeException ex) {
      clearActiveSelection();
      return Optional.empty();
    }
    if (!source.currentLayout()) {
      clearActiveSelection();
      return Log.fatal(
          IllegalStateException.class,
          "The active input cache layout is outdated; reload " + source.sourcePath() + ".");
    }
    return Optional.of(new CacheHandle(cacheFile, jdbcUrl(cacheFile), false, source));
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
        .map(cacheFile -> readMetadata(cacheFile).map(metadata -> {
          if (metadata.databaseStructure()) {
            return new CacheEntry(
                cacheFile.getFileName().toString(),
                metadata.sourcePath(), metadata.inputType(), metadata.createdMillis(),
                activeCache.map(cacheFile::equals).orElse(false), "database-metadata", false);
          }
          CacheSource source = metadata.source();
          return new CacheEntry(
              cacheFile.getFileName().toString(),
              metadata.sourcePath(),
              metadata.inputType(),
              metadata.createdMillis(),
              activeCache.map(cacheFile::equals).orElse(false),
              source.displayVariantId(),
              !source.currentLayout());
        }))
        .flatMap(Optional::stream)
        .sorted(Comparator.comparing(CacheEntry::sourcePath))
        .toList();
  }

  public static int clearForInput(String target, Map<String, String> params) {
    Optional<Path> namedCacheFile = resolveCacheFilename(target, params);
    if (namedCacheFile.isPresent()) {
      Path cacheFile = namedCacheFile.get();
      if (!isCacheFile(cacheFile)) {
        return 0;
      }
      deleteCache(cacheFile);
      clearActiveIfMissing();
      return 1;
    }

    String sourcePath = CacheSource.normalizedSourceIdentity(target);
    int cleared = 0;
    for (Path cacheFile : cacheFiles(params)) {
      if (readMetadata(cacheFile)
          .map(metadata -> sourcePath.equals(metadata.sourcePath()))
          .orElse(false)) {
        deleteCache(cacheFile);
        cleared++;
      }
    }
    clearActiveIfMissing();
    return cleared;
  }

  public static int clearOlderThan(Duration duration, Map<String, String> params) {
    long cutoffMillis = Instant.now().minus(duration).toEpochMilli();
    int cleared = 0;
    for (Path cacheFile : cacheFiles(params)) {
      if (readMetadata(cacheFile).map(metadata -> metadata.createdMillis() < cutoffMillis).orElse(false)) {
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

  public static Path cacheFile(String identityText, Map<String, String> params) {
    Path root = cacheRoot(params);
    createDirectories(root);
    return cacheFiles(params).stream()
        .filter(cacheFile -> readMetadata(cacheFile)
            .map(metadata -> identityText.equals(metadata.identityText()))
            .orElse(false))
        .findFirst()
        .orElseGet(() -> unusedCacheFile(root, PersistentCache::generateCacheName));
  }

  static Path unusedCacheFile(Path root, Supplier<String> names) {
    while (true) {
      String name = names.get();
      if (name == null || !name.matches(CACHE_NAME_PATTERN)) {
        return Log.fatal(IllegalStateException.class,
            "Jname generated an invalid cache name: " + name);
      }
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

  private static String existingJdbcUrl(Path cacheFile) {
    return jdbcUrl(cacheFile) + ";IFEXISTS=TRUE";
  }

  private static String databasePath(Path cacheFile) {
    String path = cacheFile.toAbsolutePath().normalize().toString();
    if (!path.endsWith(H2_DATABASE_SUFFIX)) {
      return Log.fatal(IllegalArgumentException.class, "Invalid H2 cache filename: " + cacheFile);
    }
    return path.substring(0, path.length() - H2_DATABASE_SUFFIX.length());
  }

  private static Optional<Metadata> readMetadata(Path cacheFile) {
    if (!Files.exists(cacheFile)) {
      return Optional.empty();
    }

    try (Connection connection = connect(cacheFile, true)) {
      return readMetadata(connection);
    } catch (SQLException ex) {
      Log.debug("Could not read cache metadata [{}]: {}", cacheFile, ex.getMessage());
      return Optional.empty();
    }
  }

  private static Optional<Metadata> readMetadata(Connection connection) {
    String sql = """
        select source_path, input_type, identity_text, created_millis
        from cache_metadata
        where id = ?
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, METADATA_ROW_ID);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          return Optional.empty();
        }
        return Optional.of(new Metadata(
            resultSet.getString("source_path"),
            resultSet.getString("input_type"),
            resultSet.getString("identity_text"),
            resultSet.getLong("created_millis")));
      }
    } catch (SQLException ex) {
      return Optional.empty();
    }
  }

  private static void writeMetadata(Path cacheFile, CacheSource source) {
    try (Connection connection = connect(cacheFile, false)) {
      createMetadataTable(connection);
      long now = Instant.now().toEpochMilli();
      try (PreparedStatement delete = connection.prepareStatement("delete from cache_metadata where id = ?")) {
        delete.setInt(1, METADATA_ROW_ID);
        delete.executeUpdate();
      }
      String sql = """
          insert into cache_metadata (
            id, source_path, input_type, identity_text, created_millis
          ) values (?, ?, ?, ?, ?)
          """;
      try (PreparedStatement insert = connection.prepareStatement(sql)) {
        insert.setInt(1, METADATA_ROW_ID);
        insert.setString(2, source.sourcePath());
        insert.setString(3, source.inputType().name());
        insert.setString(4, source.identityText());
        insert.setLong(5, now);
        insert.executeUpdate();
      }
    } catch (SQLException ex) {
      Log.fatal(IllegalStateException.class, "Could not write cache metadata: " + cacheFile, ex);
    }
  }

  private static void createMetadataTable(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("""
          create table if not exists cache_metadata (
            id integer primary key,
            source_path varchar(4096) not null,
            input_type varchar(32) not null,
            identity_text varchar(65535) not null,
            created_millis bigint not null
          )
          """);
    }
  }

  private static void createArtifactTable(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.execute("create schema if not exists nq_internal");
      statement.execute("""
          create table if not exists nq_internal.cache_artifact (
            id integer primary key,
            identity_text varchar(65535) not null,
            artifact_version integer not null,
            refreshed_millis bigint not null,
            expiry_hours bigint not null,
            payload blob not null
          )
          """);
    }
  }

  private static void writeArtifactMetadata(
      Connection connection,
      String identityText,
      String displayName) throws SQLException {
    try (PreparedStatement delete = connection.prepareStatement("delete from cache_metadata where id = ?")) {
      delete.setInt(1, METADATA_ROW_ID);
      delete.executeUpdate();
    }
    try (PreparedStatement insert = connection.prepareStatement("""
        insert into cache_metadata (
          id, source_path, input_type, identity_text, created_millis
        ) values (?, ?, ?, ?, ?)
        """)) {
      insert.setInt(1, METADATA_ROW_ID);
      insert.setString(2, displayName);
      insert.setString(3, "DATABASE_STRUCTURE");
      insert.setString(4, identityText);
      insert.setLong(5, Instant.now().toEpochMilli());
      insert.executeUpdate();
    }
  }

  private static Connection connect(Path cacheFile, boolean existingOnly) throws SQLException {
    try {
      Class.forName("org.h2.Driver");
    } catch (ClassNotFoundException ex) {
      return Log.fatal(IllegalStateException.class, "H2 driver is not available", ex);
    }
    return DriverManager.getConnection(existingOnly ? existingJdbcUrl(cacheFile) : jdbcUrl(cacheFile), "sa", "");
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
    return filename.matches(CACHE_NAME_PATTERN + "\\.mv\\.db");
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
    Optional<Path> activeCache = configuredActiveCacheFile();
    if (activeCache.isPresent() && !Files.exists(activeCache.get())) {
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

  static String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      return Log.fatal(IllegalStateException.class, "SHA-256 is not available", ex);
    }
  }

}
