package blater.nql.runner.sql.cache;

import blater.nql.cli.CacheNameSelection;
import blater.nql.domain.Hierarchy;
import blater.nql.execution.EngineInputLoader;
import blater.nql.runner.sql.SqlExecutor;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static blater.nql.execution.EngineParameterNames.*;

/** Opens typed temporary and persistent-cache SQL execution targets. */
public final class CacheExecution {
  private CacheExecution() {
  }

  /** Strictly loads and activates a fresh logical cache. */
  public static CacheHandle loadAndActivate(
      Path cacheDirectory,
      CacheNameSelection nameSelection,
      Map<String, String> parameters) {
    return PersistentCache.loadAndActivate(cacheDirectory, nameSelection, handle -> {
      SqlExecutor executor = openExisting(handle, parameters);
      try {
        Hierarchy input = EngineInputLoader.load(parameters);
        new HierarchyCacheLoader(executor).load(input);
      } finally {
        executor.close();
      }
    });
  }

  /** Opens and populates a temporary in-memory database from the typed input. */
  public static SqlExecutor openTemporary(Map<String, String> parameters) {
    String databaseName = "nql_" + UUID.randomUUID().toString().replace("-", "");
    String jdbcUrl = "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;NON_KEYWORDS=VALUE";
    SqlExecutor executor = new SqlExecutor(jdbcParameters(parameters, jdbcUrl));
    try {
      Hierarchy input = EngineInputLoader.load(parameters);
      new HierarchyCacheLoader(executor).load(input);
      return executor;
    } catch (RuntimeException | Error failure) {
      executor.close();
      throw failure;
    }
  }

  /** Opens an existing cache without loading or activating it. */
  public static SqlExecutor openExisting(
      CacheHandle handle,
      Map<String, String> parameters) {
    return new SqlExecutor(jdbcParameters(parameters, handle.jdbcUrl()));
  }

  private static Map<String, String> jdbcParameters(
      Map<String, String> parameters,
      String jdbcUrl) {
    Map<String, String> cacheParameters = new HashMap<>(parameters);
    cacheParameters.remove(JDBC_DRIVER);
    cacheParameters.put(JDBC_CLASS_NAME, "org.h2.Driver");
    cacheParameters.put(JDBC_DATABASE, jdbcUrl);
    cacheParameters.put(JDBC_USERNAME, "sa");
    cacheParameters.put(JDBC_PASSWORD, "");
    return cacheParameters;
  }
}
