package blater.nq.runner.sql.cache;

import blater.nq.ParameterParser;
import blater.nq.runner.sql.SqlExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheExecutionTest {
  @TempDir
  Path tempDir;

  @BeforeEach
  void clearActiveCacheSelection() throws Exception {
    Files.deleteIfExists(PersistentCache.configFile(Map.of(
        ParameterParser.STATE_DIR_PARAM, tempDir.resolve("state").toString())));
  }

  @Test
  void eachStandaloneLoadCreatesAndActivatesANewCache() throws Exception {
    Path input = write("standalone.json", """
        { "data": { "customer": [{ "id": "C1" }] } }
        """);
    Map<String, String> parameters = cacheParameters(input, "standalone-cache");

    CacheExecution.loadAndActivate(parameters);
    Path first = PersistentCache.active(parameters).orElseThrow().cacheFile();
    CacheExecution.loadAndActivate(parameters);

    CacheHandle active = PersistentCache.active(parameters).orElseThrow();
    assertFalse(first.equals(active.cacheFile()));
  }

  @Test
  void opensTheActiveCacheWhenNoInputOrJdbcConnectionIsSupplied() throws Exception {
    Path input = write("active.json", """
        { "data": { "customer": [{ "id": "ACTIVE" }] } }
        """);
    Map<String, String> parameters = cacheParameters(input, "active-cache");
    CacheExecution.loadAndActivate(parameters);

    SqlExecutor executor = CacheExecution.openForQuery(Map.of(
        ParameterParser.STATE_DIR_PARAM, parameters.get(ParameterParser.STATE_DIR_PARAM))).orElseThrow();
    try (var rows = executor.query("select id from customer")) {
      assertTrue(rows.next());
      assertEquals("ACTIVE", rows.stringValue(1));
    } finally {
      executor.close();
    }
  }

  @Test
  void explicitCacheReplacesExternalJdbcSettings() throws Exception {
    Path input = write("explicit.json", """
        { "data": { "customer": [{ "id": "CACHED" }] } }
        """);
    Map<String, String> parameters = new HashMap<>(cacheParameters(input, "explicit-cache"));
    parameters.put(ParameterParser.JDBC_DRIVER_PARAM, "postgresql");
    parameters.put(ParameterParser.JDBC_DATABASE_PARAM, "jdbc:postgresql://invalid/external");
    parameters.put(ParameterParser.JDBC_USERNAME_PARAM, "external");
    parameters.put(ParameterParser.JDBC_PASSWORD_PARAM, "secret");

    SqlExecutor executor = CacheExecution.openForQuery(parameters).orElseThrow();
    try (var rows = executor.query("select id from customer")) {
      assertTrue(rows.next());
      assertEquals("CACHED", rows.stringValue(1));
    } finally {
      executor.close();
    }
  }

  @Test
  void ephemeralCacheLoadsCurrentInputWithoutCreatingPersistentState() throws Exception {
    Path input = write("ephemeral.json", """
        { "data": { "customer": [{ "id": "FIRST" }] } }
        """);
    Path cacheDir = tempDir.resolve("ephemeral-cache");
    Map<String, String> parameters = Map.of(
        ParameterParser.INPUT_FILENAME, input.toString(),
        ParameterParser.STATE_DIR_PARAM, cacheDir.toString());

    SqlExecutor first = CacheExecution.openForQuery(parameters).orElseThrow();
    try (var rows = first.query("select id from customer")) {
      assertTrue(rows.next());
      assertEquals("FIRST", rows.stringValue(1));
    } finally {
      first.close();
    }

    Files.writeString(input, """
        { "data": { "customer": [{ "id": "SECOND" }] } }
        """);
    SqlExecutor second = CacheExecution.openForQuery(parameters).orElseThrow();
    try (var rows = second.query("select id from customer")) {
      assertTrue(rows.next());
      assertEquals("SECOND", rows.stringValue(1));
    } finally {
      second.close();
    }

    assertFalse(Files.exists(cacheDir));
    assertTrue(PersistentCache.active(parameters).isEmpty());
  }

  @Test
  void leavesExternalJdbcExecutionToScriptRunner() {
    Map<String, String> parameters = Map.of(
        ParameterParser.JDBC_CLASS_NAME_PARAM, "org.h2.Driver",
        ParameterParser.JDBC_DATABASE_PARAM, "jdbc:h2:mem:external");

    assertTrue(CacheExecution.openForQuery(parameters).isEmpty());
  }

  private Map<String, String> cacheParameters(Path input, String cacheDirectory) {
    return Map.of(
        ParameterParser.CACHE_MODE_PARAM, "true",
        ParameterParser.INPUT_FILENAME, input.toString(),
        ParameterParser.STATE_DIR_PARAM, tempDir.resolve(cacheDirectory).toString());
  }

  private Path write(String name, String content) throws Exception {
    Path path = tempDir.resolve(name);
    Files.writeString(path, content);
    return path;
  }
}
