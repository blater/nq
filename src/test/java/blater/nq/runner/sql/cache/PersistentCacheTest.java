package blater.nq.runner.sql.cache;

import blater.nq.ParameterParser;
import blater.nq.inputreader.InputType;
import blater.nq.runner.inference.KeyInference;
import blater.nq.runner.sql.SqlExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentCacheTest {
  @TempDir
  Path tempDir;

  @BeforeEach
  void clearActiveCacheSelection() throws Exception {
    Files.deleteIfExists(PersistentCache.configFile());
  }

  @Test
  void reusesCacheWhenInputFileMatchesMetadata() throws Exception {
    Path input = write("input.json", "{\"name\":\"Fred\"}");
    Map<String, String> params = cacheParams();

    CacheHandle first = PersistentCache.prepare(source(input), params);
    assertTrue(first.needsLoad());
    PersistentCache.markLoaded(first);

    CacheHandle second = PersistentCache.prepare(source(input), params);

    assertFalse(second.needsLoad());
    assertEquals(first.cacheFile(), second.cacheFile());
  }

  @Test
  void reusesCacheWhenInputFileChanges() throws Exception {
    Path input = write("input.json", "{\"name\":\"Fred\"}");
    Map<String, String> params = cacheParams();

    CacheHandle first = PersistentCache.prepare(source(input), params);
    PersistentCache.markLoaded(first);

    Files.writeString(input, "{\"name\":\"Fred\",\"extra\":\"changed\"}");
    CacheHandle second = PersistentCache.prepare(source(input), params);

    assertFalse(second.needsLoad());
    assertEquals(first.cacheFile(), second.cacheFile());
  }

  @Test
  void reusesCacheWhenInputFileNoLongerExists() throws Exception {
    Path input = write("input.json", "{\"name\":\"Fred\"}");
    Map<String, String> params = cacheParams();

    CacheHandle first = PersistentCache.prepare(source(input), params);
    PersistentCache.markLoaded(first);
    Files.delete(input);

    CacheHandle second = PersistentCache.prepare(source(input), params);

    assertFalse(second.needsLoad());
    assertEquals(first.cacheFile(), second.cacheFile());
  }

  @Test
  void clearsSpecificCacheForInputFile() throws Exception {
    Path firstInput = write("first.json", "{\"name\":\"Fred\"}");
    Path secondInput = write("second.json", "{\"name\":\"Wilma\"}");
    Map<String, String> params = cacheParams();
    CacheHandle first = preparedCache(firstInput, params);
    CacheHandle second = preparedCache(secondInput, params);

    int cleared = PersistentCache.clearForInput(firstInput.toString(), params);

    assertEquals(1, cleared);
    assertFalse(Files.exists(first.cacheFile()));
    assertTrue(Files.exists(second.cacheFile()));
  }

  @Test
  void clearsSpecificCacheByBareCacheFilename() throws Exception {
    Map<String, String> params = cacheParams();
    CacheHandle first = preparedCache(write("first.json", "{\"name\":\"Fred\"}"), params);
    CacheHandle second = preparedCache(write("second.json", "{\"name\":\"Wilma\"}"), params);

    int cleared = PersistentCache.clearForInput(
        first.cacheFile().getFileName().toString(), params);

    assertEquals(1, cleared);
    assertFalse(Files.exists(first.cacheFile()));
    assertTrue(Files.exists(second.cacheFile()));
  }

  @Test
  void usesSpecificCacheByBareCacheFilename() throws Exception {
    Map<String, String> params = cacheParams();
    CacheHandle first = preparedCache(write("first.json", "{\"name\":\"Fred\"}"), params);
    CacheHandle second = preparedCache(write("second.json", "{\"name\":\"Wilma\"}"), params);
    PersistentCache.activate(second);

    CacheHandle selected = PersistentCache.use(
        first.cacheFile().getFileName().toString(), params);

    assertEquals(first.cacheFile(), selected.cacheFile());
    assertEquals(first.cacheFile(), PersistentCache.active().orElseThrow().cacheFile());
  }

  @Test
  void standardInputCacheIdentifierCanSelectAndClearCache() throws Exception {
    Map<String, String> params = cacheParams();
    String sourceId = "stdin:json:sha256:0123456789abcdef";
    CacheHandle cached = preparedCache(new CacheSource(sourceId, InputType.JSON, ""), params);

    CacheHandle selected = PersistentCache.use(sourceId, params);

    assertEquals(cached.cacheFile(), selected.cacheFile());
    assertEquals(1, PersistentCache.clearForInput(sourceId, params));
    assertFalse(Files.exists(cached.cacheFile()));
  }

  @Test
  void parquetRecordNamesCreateDistinctCacheVariantsForSameFile() throws Exception {
    Path input = write("customers.parquet", "not used by this test");
    Map<String, String> params = cacheParams();
    CacheHandle customer = preparedCache(parquetSource(input, "customer"), params);
    CacheHandle account = preparedCache(parquetSource(input, "account"), params);

    assertTrue(Files.exists(customer.cacheFile()));
    assertTrue(Files.exists(account.cacheFile()));
    assertFalse(customer.cacheFile().equals(account.cacheFile()));
  }

  @Test
  void materializationConfigurationCreatesDistinctCanonicalVariants() throws Exception {
    Path input = write("configured.json", "{}");
    Map<String, String> defaults = cacheParams();
    Map<String, String> configured = Map.of(
        ParameterParser.CACHE_DIR_PARAM, PersistentCache.cacheRoot(defaults).toString(),
        ParameterParser.ANONYMOUS_COLLECTIONS_PARAM, "error",
        ParameterParser.RELATION_ALIAS_PREFIX + "000000", "/=records");

    CacheHandle defaultCache = preparedCache(CacheSource.from(
        input.toString(), InputType.JSON, defaults), defaults);
    CacheHandle configuredCache = preparedCache(CacheSource.from(
        input.toString(), InputType.JSON, configured), configured);

    assertFalse(defaultCache.cacheFile().equals(configuredCache.cacheFile()));
    assertEquals(2, PersistentCache.listCaches(defaults).size());
    assertTrue(PersistentCache.listCaches(defaults).stream()
        .map(CacheEntry::variantId).anyMatch(value -> value.startsWith("config-")));
    assertThrows(IllegalArgumentException.class,
        () -> PersistentCache.use(input.toString(), defaults));
    assertEquals(configuredCache.cacheFile(),
        PersistentCache.use(input.toString(), configured).cacheFile());
  }

  @Test
  void outdatedInputCachesRemainListableButCannotBeSelected() throws Exception {
    Path input = write("old.json", "{}");
    Map<String, String> params = cacheParams();
    CacheSource oldSource = new CacheSource(
        input.toAbsolutePath().normalize().toString(), InputType.JSON, "", "");
    CacheHandle oldCache = preparedCache(oldSource, params);

    CacheEntry listed = PersistentCache.listCaches(params).getFirst();
    assertTrue(listed.outdated());
    assertEquals("outdated", listed.variantId());
    assertThrows(IllegalArgumentException.class,
        () -> PersistentCache.use(input.toString(), params));
    assertThrows(IllegalArgumentException.class,
        () -> PersistentCache.use(oldCache.cacheFile().getFileName().toString(), params));

    PersistentCache.activate(oldCache);
    assertThrows(IllegalStateException.class, PersistentCache::active);
    assertTrue(PersistentCache.active().isEmpty());
  }

  @Test
  void useCacheRequiresParquetVariantWhenAPathHasMultipleCaches() throws Exception {
    Path input = write("customers.parquet", "not used by this test");
    Map<String, String> params = cacheParams();
    CacheHandle customer = preparedCache(parquetSource(input, "customer"), params);
    preparedCache(parquetSource(input, "account"), params);

    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class,
        () -> PersistentCache.use(input.toString(), params));
    CacheHandle selected = PersistentCache.use(
        input.toString(),
        Map.of(
            ParameterParser.CACHE_DIR_PARAM, PersistentCache.cacheRoot(params).toString(),
            ParameterParser.PARQUET_RECORD_PARAM, "customer"));

    assertTrue(exception.getMessage().contains("specify --parquet-record"));
    assertEquals(customer.cacheFile(), selected.cacheFile());
    assertEquals(customer.cacheFile(), PersistentCache.active().orElseThrow().cacheFile());
  }

  @Test
  void parquetRootNameDoesNotCreateAnotherCacheVariant() throws Exception {
    Path input = write("customers.parquet", "not used by this test");
    Map<String, String> params = cacheParams();
    CacheHandle first = preparedCache(CacheSource.from(
        input.toString(),
        InputType.PARQUET,
        Map.of(ParameterParser.PARQUET_ROOT_PARAM, "customers")), params);
    CacheHandle second = PersistentCache.prepare(CacheSource.from(
        input.toString(),
        InputType.PARQUET,
        Map.of(ParameterParser.PARQUET_ROOT_PARAM, "accounts")), params);

    assertFalse(second.needsLoad());
    assertEquals(first.cacheFile(), second.cacheFile());
  }

  @Test
  void explicitEmptyParquetRecordNameIsDistinctFromNoOverride() throws Exception {
    Path input = write("customers.parquet", "not used by this test");

    CacheSource inferred = CacheSource.from(input.toString(), InputType.PARQUET);
    CacheSource emptyOverride = CacheSource.from(
        input.toString(),
        InputType.PARQUET,
        Map.of(ParameterParser.PARQUET_RECORD_PARAM, ""));

    assertFalse(PersistentCache.cacheFile(inferred.identityText(), Map.of())
        .equals(PersistentCache.cacheFile(emptyOverride.identityText(), Map.of())));
  }

  @Test
  void clearForInputRemovesAllCacheVariantsForSameSourcePath() throws Exception {
    Path input = write("customers.parquet", "not used by this test");
    Path otherInput = write("other.parquet", "not used by this test");
    Map<String, String> params = cacheParams();
    CacheHandle customer = preparedCache(parquetSource(input, "customer"), params);
    CacheHandle account = preparedCache(parquetSource(input, "account"), params);
    CacheHandle other = preparedCache(parquetSource(otherInput, "customer"), params);

    int cleared = PersistentCache.clearForInput(input.toString(), params);

    assertEquals(2, cleared);
    assertFalse(Files.exists(customer.cacheFile()));
    assertFalse(Files.exists(account.cacheFile()));
    assertTrue(Files.exists(other.cacheFile()));
  }

  @Test
  void clearsAllCaches() throws Exception {
    Map<String, String> params = cacheParams();
    preparedCache(write("first.json", "{\"name\":\"Fred\"}"), params);
    preparedCache(write("second.json", "{\"name\":\"Wilma\"}"), params);

    int cleared = PersistentCache.clearAll(params);

    assertEquals(2, cleared);
    assertEquals(0, cacheFileCount(params));
  }

  @Test
  void clearEntryPointClearsExistingCache() throws Exception {
    Map<String, String> params = cacheParams(ParameterParser.CACHE_CLEAR_ALL_PARAM, "true");
    CacheHandle handle = preparedCache(write("cached.json", "{\"name\":\"Fred\"}"), params);
    assertTrue(Files.exists(handle.cacheFile()));

    int cleared = PersistentCache.clear(params);

    assertEquals(1, cleared);
    assertFalse(Files.exists(handle.cacheFile()));
  }

  @Test
  void listsCachesFromCacheDatabase() throws Exception {
    Map<String, String> params = cacheParams();
    Path firstInput = write("first.json", "{\"name\":\"Fred\"}");
    Path secondInput = write("second.json", "{\"name\":\"Wilma\"}");
    preparedCache(secondInput, params);
    preparedCache(firstInput, params);
    Files.createDirectories(PersistentCache.cacheRoot(params).resolve("orphan-without-metadata"));

    var entries = PersistentCache.listCaches(params);

    assertEquals(2, entries.size());
    assertEquals(firstInput.toAbsolutePath().normalize().toString(), entries.get(0).sourcePath());
    assertEquals(InputType.JSON.name(), entries.get(0).inputType());
    assertTrue(entries.get(0).createdMillis() > 0);
    assertEquals(secondInput.toAbsolutePath().normalize().toString(), entries.get(1).sourcePath());
  }

  @Test
  void listEntryPointLeavesExistingCacheReusable() throws Exception {
    Map<String, String> params = cacheParams(ParameterParser.CACHE_LIST_PARAM, "true");
    Path input = write("cached.json", "{\"name\":\"Fred\"}");
    CacheHandle handle = preparedCache(input, params);

    PersistentCache.list(params);

    CacheHandle reused = PersistentCache.prepare(source(input), params);
    assertEquals(handle.cacheFile(), reused.cacheFile());
    assertFalse(reused.needsLoad());
  }

  @Test
  void clearsCachesOlderThanDurationUsingCreatedTime() throws Exception {
    Map<String, String> params = cacheParams();
    CacheHandle oldCache = preparedCache(write("old.json", "{\"name\":\"Fred\"}"), params);
    CacheHandle recentCache = preparedCache(write("recent.json", "{\"name\":\"Wilma\"}"), params);
    forceCreated(oldCache, "1");

    int cleared = PersistentCache.clearOlderThan(Duration.ofDays(1), params);

    assertEquals(1, cleared);
    assertFalse(Files.exists(oldCache.cacheFile()));
    assertTrue(Files.exists(recentCache.cacheFile()));
  }

  @Test
  void activeCacheIsListedAndClearedWithoutLosingOtherConfiguration() throws Exception {
    Map<String, String> params = cacheParams();
    Path input = write("active.json", "{\"name\":\"Fred\"}");
    CacheHandle handle = preparedCache(input, params);
    Path configFile = PersistentCache.configFile();
    Files.createDirectories(configFile.getParent());
    Files.writeString(configFile, "unrelated=value\n");

    PersistentCache.activate(handle);

    assertEquals(handle.cacheFile(), PersistentCache.active().orElseThrow().cacheFile());
    assertTrue(PersistentCache.listCaches(params).getFirst().active());
    assertTrue(Files.readString(configFile).contains("unrelated=value"));

    PersistentCache.clearForInput(input.toString(), params);

    assertTrue(PersistentCache.active().isEmpty());
    assertTrue(Files.readString(configFile).contains("unrelated=value"));
  }

  @Test
  void parsesMinuteHourAndDayDurations() {
    assertEquals(Duration.ofMinutes(30), PersistentCache.parseDuration("30m"));
    assertEquals(Duration.ofHours(6), PersistentCache.parseDuration("6hours"));
    assertEquals(Duration.ofDays(2), PersistentCache.parseDuration("2days"));
  }

  @Test
  void storesDatabaseStructureArtifactsThroughTheExistingCacheLifecycle() {
    Map<String, String> params = cacheParams();
    String identity = "url=jdbc:test\n";
    Path cacheFile = PersistentCache.cacheFile(identity, params);
    byte[] payload = {1, 2, 3, 4};

    PersistentCache.writeArtifact(
        cacheFile,
        identity,
        "Test database",
        1,
        24,
        payload);

    CachedArtifact cached = PersistentCache.readArtifact(
        cacheFile, identity).orElseThrow();
    assertEquals(1, cached.version());
    assertEquals(24, cached.expiryHours());
    assertTrue(java.util.Arrays.equals(payload, cached.payload()));
    assertEquals("DATABASE_STRUCTURE", PersistentCache.listCaches(params).getFirst().inputType());

    PersistentCache.setArtifactExpiry(cacheFile, identity, 0);
    assertEquals(0, PersistentCache.readArtifact(
        cacheFile, identity).orElseThrow().expiryHours());
    assertEquals(1, PersistentCache.clearAll(params));
  }

  @Test
  void inputCacheStoresItsDatabaseStructureInTheSameH2Database() throws Exception {
    Map<String, String> params = cacheParams();
    CacheHandle handle = preparedCache(write("input.json", "{}"), params);
    try (var connection = DriverManager.getConnection(handle.jdbcUrl(), "sa", "");
         var statement = connection.createStatement()) {
      statement.execute("create table item (id integer primary key)");
    }
    Map<String, String> jdbc = Map.of(
        ParameterParser.JDBC_CLASS_NAME_PARAM, "org.h2.Driver",
        ParameterParser.JDBC_DATABASE_PARAM, handle.jdbcUrl(),
        ParameterParser.JDBC_USERNAME_PARAM, "sa",
        ParameterParser.JDBC_PASSWORD_PARAM, "",
        ParameterParser.CACHE_DIR_PARAM, PersistentCache.cacheRoot(params).toString());

    SqlExecutor executor = new SqlExecutor(jdbc, handle.cacheFile(), handle.source().identityText());
    try {
      KeyInference.refresh(executor, jdbc);
    } finally {
      executor.close();
    }

    assertEquals(1, cacheFileCount(params));
    try (var connection = DriverManager.getConnection(handle.jdbcUrl(), "sa", "");
         var result = connection.createStatement().executeQuery(
             "select count(*) from nq_internal.cache_artifact")) {
      result.next();
      assertEquals(1, result.getInt(1));
    }
  }

  private CacheHandle preparedCache(Path input, Map<String, String> params) throws Exception {
    return preparedCache(source(input), params);
  }

  private CacheHandle preparedCache(CacheSource source, Map<String, String> params) throws Exception {
    CacheHandle handle = PersistentCache.prepare(source, params);
    PersistentCache.markLoaded(handle);
    return handle;
  }

  private void forceCreated(CacheHandle handle, String millis) throws Exception {
    try (var connection = DriverManager.getConnection(handle.jdbcUrl(), "sa", "");
         var statement = connection.prepareStatement("update cache_metadata set created_millis = ? where id = 1")) {
      statement.setLong(1, Long.parseLong(millis));
      statement.executeUpdate();
    }
  }

  private int cacheFileCount(Map<String, String> params) throws Exception {
    try (var paths = Files.list(PersistentCache.cacheRoot(params))) {
      return (int) paths.filter(path -> path.getFileName().toString().endsWith(".mv.db")).count();
    }
  }

  private Map<String, String> cacheParams() {
    return Map.of(ParameterParser.CACHE_DIR_PARAM, tempDir.resolve("cache").toString());
  }

  private Map<String, String> cacheParams(String key, String value) {
    return Map.of(
        ParameterParser.CACHE_DIR_PARAM, tempDir.resolve("cache").toString(),
        key, value);
  }

  private CacheSource source(Path input) {
    return CacheSource.from(input.toString(), InputType.JSON);
  }

  private CacheSource parquetSource(Path input, String recordName) {
    return CacheSource.from(
        input.toString(),
        InputType.PARQUET,
        Map.of(ParameterParser.PARQUET_RECORD_PARAM, recordName));
  }

  private Path write(String name, String content) throws Exception {
    Path path = tempDir.resolve(name);
    Files.writeString(path, content);
    return path;
  }
}
