package blater.nq.runner.sql.cache;

import blater.nq.ParameterParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
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
  void createsANewCacheWithoutSearchingExistingMetadata() throws Exception {
    Path input = write("input.json", "{\"name\":\"Fred\"}");
    Map<String, String> params = cacheParams();

    CacheHandle first = PersistentCache.prepare(params);
    assertTrue(first.needsLoad());
    CacheHandle second = PersistentCache.prepare(params);

    assertTrue(second.needsLoad());
    assertFalse(first.cacheFile().equals(second.cacheFile()));
  }

  @Test
  void retriesWhenGeneratedCacheNameAlreadyExists() throws Exception {
    Path root = PersistentCache.cacheRoot(cacheParams());
    Files.createDirectories(root);
    Files.createFile(root.resolve("calm-otter.mv.db"));
    var generated = List.of("calm-otter", "bright-fox").iterator();

    Path cacheFile = PersistentCache.unusedCacheFile(root, generated::next);

    assertEquals(root.resolve("bright-fox.mv.db"), cacheFile);
  }

  @Test
  void clearsSpecificCacheByBareCacheFilename() throws Exception {
    Map<String, String> params = cacheParams();
    CacheHandle first = preparedCache(write("first.json", "{\"name\":\"Fred\"}"), params);
    CacheHandle second = preparedCache(write("second.json", "{\"name\":\"Wilma\"}"), params);

    int cleared = PersistentCache.clearNamed(
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
  void cacheCanBeSelectedAndClearedByFilename() throws Exception {
    Map<String, String> params = cacheParams();
    CacheHandle cached = preparedCache(params);

    String filename = cached.cacheFile().getFileName().toString();
    CacheHandle selected = PersistentCache.use(filename, params);

    assertEquals(cached.cacheFile(), selected.cacheFile());
    assertEquals(1, PersistentCache.clearNamed(filename, params));
    assertFalse(Files.exists(cached.cacheFile()));
  }

  @Test
  void selectsCachesByFilename() throws Exception {
    Path firstInput = write("first.json", "{}");
    Path secondInput = write("second.json", "{}");
    Map<String, String> params = cacheParams();
    CacheHandle first = preparedCache(firstInput, params);
    CacheHandle second = preparedCache(secondInput, params);

    CacheHandle selected = PersistentCache.use(
        first.cacheFile().getFileName().toString(), params);

    assertEquals(first.cacheFile(), selected.cacheFile());
    assertFalse(second.cacheFile().equals(selected.cacheFile()));
    assertEquals(first.cacheFile(), PersistentCache.active().orElseThrow().cacheFile());
  }

  @Test
  void repeatedPreparationAlwaysAllocatesANewFilename() throws Exception {
    Path input = write("customers.json", "{}");
    Map<String, String> params = cacheParams();
    CacheHandle first = preparedCache(input, params);
    CacheHandle second = PersistentCache.prepare(params);

    assertTrue(second.needsLoad());
    assertFalse(first.cacheFile().equals(second.cacheFile()));
  }

  @Test
  void clearNamedRequiresACacheFilename() throws Exception {
    Path input = write("customers.json", "{}");
    Map<String, String> params = cacheParams();
    CacheHandle cache = preparedCache(input, params);

    assertThrows(IllegalArgumentException.class,
        () -> PersistentCache.clearNamed(input.toString(), params));
    assertTrue(Files.exists(cache.cacheFile()));
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
  void listsCacheFilesWithoutOpeningTheirDatabases() throws Exception {
    Map<String, String> params = cacheParams();
    Path firstInput = write("first.json", "{\"name\":\"Fred\"}");
    Path secondInput = write("second.json", "{\"name\":\"Wilma\"}");
    preparedCache(secondInput, params);
    preparedCache(firstInput, params);
    Files.createDirectories(PersistentCache.cacheRoot(params).resolve("orphan-without-metadata"));

    var entries = PersistentCache.listCaches(params);

    assertEquals(2, entries.size());
    assertTrue(entries.get(0).modifiedMillis() > 0);
    assertTrue(entries.stream().allMatch(entry -> entry.cacheFilename().endsWith(".mv.db")));
  }

  @Test
  void listEntryPointDoesNotAffectNewCacheAllocation() throws Exception {
    Map<String, String> params = cacheParams(ParameterParser.CACHE_LIST_PARAM, "true");
    Path input = write("cached.json", "{\"name\":\"Fred\"}");
    CacheHandle handle = preparedCache(input, params);

    PersistentCache.list(params);

    CacheHandle next = PersistentCache.prepare(params);
    assertFalse(handle.cacheFile().equals(next.cacheFile()));
    assertTrue(next.needsLoad());
  }

  @Test
  void clearsCachesOlderThanDurationUsingModifiedTime() throws Exception {
    Map<String, String> params = cacheParams();
    CacheHandle oldCache = preparedCache(write("old.json", "{\"name\":\"Fred\"}"), params);
    CacheHandle recentCache = preparedCache(write("recent.json", "{\"name\":\"Wilma\"}"), params);
    forceModified(oldCache, "1");

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

    PersistentCache.clearNamed(handle.cacheFile().getFileName().toString(), params);

    assertTrue(PersistentCache.active().isEmpty());
    assertTrue(Files.readString(configFile).contains("unrelated=value"));
  }

  @Test
  void parsesMinuteHourAndDayDurations() {
    assertEquals(Duration.ofMinutes(30), PersistentCache.parseDuration("30m"));
    assertEquals(Duration.ofHours(6), PersistentCache.parseDuration("6hours"));
    assertEquals(Duration.ofDays(2), PersistentCache.parseDuration("2days"));
  }

  private CacheHandle preparedCache(Path input, Map<String, String> params) throws Exception {
    return preparedCache(params);
  }

  private CacheHandle preparedCache(Map<String, String> params) throws Exception {
    CacheHandle handle = PersistentCache.prepare(params);
    try (var ignored = DriverManager.getConnection(handle.jdbcUrl(), "sa", "")) {
      // H2 creates the cache file.
    }
    return handle;
  }

  private void forceModified(CacheHandle handle, String millis) throws Exception {
    Files.setLastModifiedTime(
        handle.cacheFile(), java.nio.file.attribute.FileTime.fromMillis(Long.parseLong(millis)));
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

  private Path write(String name, String content) throws Exception {
    Path path = tempDir.resolve(name);
    Files.writeString(path, content);
    return path;
  }
}
