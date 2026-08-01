package blater.nq.runner.sql.cache;

import blater.nq.domain.Hierarchy;
import blater.nq.inputreader.InputReader;
import blater.nq.inputreader.InputType;
import blater.nq.runner.sql.SqlExecutor;
import blater.nq.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static blater.nq.ParameterParser.CACHE_MODE_PARAM;
import static blater.nq.ParameterParser.INPUT_FILENAME;
import static blater.nq.ParameterParser.JDBC_CLASS_NAME_PARAM;
import static blater.nq.ParameterParser.JDBC_DATABASE_PARAM;
import static blater.nq.ParameterParser.JDBC_DRIVER_PARAM;
import static blater.nq.ParameterParser.JDBC_PASSWORD_PARAM;
import static blater.nq.ParameterParser.JDBC_USERNAME_PARAM;
import static blater.nq.ParameterParser.INPUT_TYPE_PARAM;

/*
 * Responsibility: Selects and prepares a cache-backed SQL execution
 * connection for standalone loading or script queries.
 */
public final class CacheExecution {
  private CacheExecution() { }

  public static void loadAndActivate(Map<String, String> parameters) {
    CacheHandle handle = explicitHandle(parameters);
    SqlExecutor executor = open(handle, parameters);
    try {
      PersistentCache.activate(handle);
    } finally {
      executor.close();
    }
  }

  public static Optional<SqlExecutor> openForQuery(Map<String, String> parameters) {
    if (usesEphemeralCache(parameters)) {
      return Optional.of(openEphemeral(parameters));
    }

    Optional<CacheHandle> selected = selectedHandle(parameters);
    if (selected.isEmpty()) {
      return Optional.empty();
    }

    CacheHandle handle = selected.get();
    SqlExecutor executor = open(handle, parameters);
    try {
      if (cacheMode(parameters) && parameters.containsKey(INPUT_FILENAME)) {
        PersistentCache.activate(handle);
      }
      return Optional.of(executor);
    } catch (RuntimeException | Error ex) {
      executor.close();
      throw ex;
    }
  }

  private static Optional<CacheHandle> selectedHandle(Map<String, String> parameters) {
    if (cacheMode(parameters)) {
      if (parameters.containsKey(INPUT_FILENAME)) {
        return Optional.of(explicitHandle(parameters));
      }
      return Optional.of(activeHandle());
    }
    if (!parameters.containsKey(INPUT_FILENAME) && !jdbcConfigured(parameters)) {
      return Optional.of(activeHandle());
    }
    return Optional.empty();
  }

  private static CacheHandle explicitHandle(Map<String, String> parameters) {
    requiredInput(parameters);
    return PersistentCache.prepare(parameters);
  }

  private static CacheHandle activeHandle() {
    return PersistentCache.active().orElseGet(() -> Log.fatal(
        IllegalStateException.class,
        "No active cache or JDBC connection is configured."));
  }

  private static SqlExecutor open(
      CacheHandle handle,
      Map<String, String> parameters) {
    SqlExecutor executor = new SqlExecutor(jdbcParameters(parameters, handle.jdbcUrl()));
    try {
      loadIfNeeded(handle, parameters, executor);
      return executor;
    } catch (RuntimeException | Error ex) {
      executor.close();
      throw ex;
    }
  }

  private static SqlExecutor openEphemeral(Map<String, String> parameters) {
    String databaseName = "nq_" + UUID.randomUUID().toString().replace("-", "");
    String jdbcUrl = "jdbc:h2:mem:" + databaseName + ";MODE=MySQL;NON_KEYWORDS=VALUE";
    SqlExecutor executor = new SqlExecutor(jdbcParameters(parameters, jdbcUrl));
    try {
      String inputFilename = requiredInput(parameters);
      InputType inputType = inputType(inputFilename, parameters);
      Hierarchy input = InputReader.of(inputType)
          .load(inputFilename, parameters);
      new HierarchyCacheLoader(executor).load(input);
      return executor;
    } catch (RuntimeException | Error ex) {
      executor.close();
      throw ex;
    }
  }

  private static void loadIfNeeded(
      CacheHandle handle,
      Map<String, String> parameters,
      SqlExecutor executor) {
    if (!handle.needsLoad()) {
      return;
    }
    String inputFilename = requiredInput(parameters);
    InputType inputType = inputType(inputFilename, parameters);
    Hierarchy input = InputReader.of(inputType).load(inputFilename, parameters);
    new HierarchyCacheLoader(executor).load(input);
  }

  private static InputType inputType(String inputFilename, Map<String, String> parameters) {
    return parameters.containsKey(INPUT_TYPE_PARAM)
        ? InputType.fromName(parameters.get(INPUT_TYPE_PARAM))
        : InputType.fromFilename(inputFilename);
  }

  private static Map<String, String> jdbcParameters(
      Map<String, String> parameters,
      String jdbcUrl) {
    Map<String, String> cacheParameters = new HashMap<>(parameters);
    cacheParameters.remove(JDBC_DRIVER_PARAM);
    cacheParameters.put(JDBC_CLASS_NAME_PARAM, "org.h2.Driver");
    cacheParameters.put(JDBC_DATABASE_PARAM, jdbcUrl);
    cacheParameters.put(JDBC_USERNAME_PARAM, "sa");
    cacheParameters.put(JDBC_PASSWORD_PARAM, "");
    return cacheParameters;
  }

  private static boolean cacheMode(Map<String, String> parameters) {
    return Boolean.parseBoolean(parameters.get(CACHE_MODE_PARAM));
  }

  public static boolean usesEphemeralCache(Map<String, String> parameters) {
    return parameters.containsKey(INPUT_FILENAME)
        && !cacheMode(parameters)
        && !jdbcConfigured(parameters);
  }

  private static boolean jdbcConfigured(Map<String, String> parameters) {
    return parameters.containsKey(JDBC_DRIVER_PARAM)
        || parameters.containsKey(JDBC_CLASS_NAME_PARAM)
        || parameters.containsKey(JDBC_DATABASE_PARAM)
        || parameters.containsKey(JDBC_USERNAME_PARAM)
        || parameters.containsKey(JDBC_PASSWORD_PARAM);
  }

  private static String requiredInput(Map<String, String> parameters) {
    String inputFilename = parameters.get(INPUT_FILENAME);
    if (inputFilename == null || inputFilename.isBlank()) {
      return Log.fatal(IllegalArgumentException.class, "--cache requires an input file.");
    }
    return inputFilename;
  }
}
